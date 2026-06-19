"""
Additive pipe property for PPL command chains.

Since PPL is a chain of piped commands, for any queries A and B:
    source=index | A | B  ===  materialize(source=index | A) then source=index_A | B

This tests that command composition is truly additive and that intermediate
materialization doesn't change results.
"""

from typing import Any, List, Optional, Dict
import random
import hashlib
from opensearchpy import OpenSearch
from opensearchpy.helpers import bulk

from ppl_correctness.datagen.context import IndexContext, Field, FieldType
from ppl_correctness.generators.ppl import PPLQueryGenerator
from ppl_correctness.properties.base import Property, PropertyViolation


class AdditivePipeProperty(Property):
    """
    Test that PPL command chains are additive.

    For commands A and B:
        source=index | A | B
    should produce the same results as:
        1. Run source=index | A, materialize to temp_index
        2. Run source=temp_index | B

    This catches bugs where:
    - Commands interact in unexpected ways
    - Field references break across command boundaries
    - Intermediate state isn't properly preserved
    - Command B depends on original index structure rather than A's output
    """

    @property
    def name(self) -> str:
        return "AdditivePipeProperty"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []
        rng = random.Random()
        generator = PPLQueryGenerator(context, rng)

        # Generate two compatible commands A and B
        # We'll test several combinations to increase coverage
        test_cases = self._generate_test_cases(context, generator, rng)

        for test_case in test_cases:
            cmd_a = test_case["cmd_a"]
            cmd_b = test_case["cmd_b"]
            description = test_case["description"]

            try:
                violation = self._test_pipe_additivity(
                    client, context, cmd_a, cmd_b, description
                )
                if violation:
                    violations.append(violation)

            except Exception as e:
                # Get more detailed traceback for debugging
                import traceback
                tb_str = ''.join(traceback.format_tb(e.__traceback__))

                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=f"A: {cmd_a}, B: {cmd_b}",
                    expected="Successful execution",
                    actual=f"{type(e).__name__}: {str(e)}",
                    message=f"Test case '{description}' failed: {e}\nTraceback: {tb_str[:500]}"
                ))

        return violations

    def _generate_test_cases(
        self,
        context: IndexContext,
        generator: PPLQueryGenerator,
        rng: random.Random
    ) -> List[Dict[str, str]]:
        """Generate test cases with compatible command pairs

        Note: We only test cases that produce simple projections to avoid
        materialization issues with complex nested structures.
        """
        test_cases = []

        # Get simple (non-nested, non-array) fields for safer testing
        simple_fields = [f for f in context.fields
                        if not f.is_array and '.' not in f.name]
        simple_groupable = [f for f in context.get_groupable_fields()
                           if not f.is_array and '.' not in f.name]

        # Test Case 1: FIELDS | WHERE (project then filter)
        # This is safer because we project simple fields first
        if len(simple_fields) >= 2:
            field1 = rng.choice(simple_fields)
            filterable_fields = [f for f in simple_fields if f.type in
                                (FieldType.INTEGER, FieldType.LONG, FieldType.BOOLEAN, FieldType.KEYWORD)]
            if filterable_fields:
                field2 = rng.choice(filterable_fields)
                test_cases.append({
                    "cmd_a": f"fields {field1.name}, {field2.name}",
                    "cmd_b": f"where isnotnull({field2.name})",
                    "description": "project_then_filter"
                })

        # Test Case 2: WHERE | STATS (filter then aggregate)
        # Using simple fields only
        if simple_fields and simple_groupable:
            comparable_field = rng.choice([f for f in simple_fields
                                          if f.type != FieldType.TEXT])
            group_field = rng.choice(simple_groupable)
            if comparable_field:
                test_cases.append({
                    "cmd_a": f"where isnotnull({comparable_field.name}) | fields {group_field.name}",
                    "cmd_b": f"stats count() by {group_field.name}",
                    "description": "filter_project_then_aggregate"
                })

        # Test Case 3: STATS | HEAD (aggregate then limit)
        if simple_groupable:
            group_field = rng.choice(simple_groupable)
            test_cases.append({
                "cmd_a": f"stats count() by {group_field.name}",
                "cmd_b": "head 5",
                "description": "aggregate_then_limit"
            })

        # Return available test cases (may be fewer if schema doesn't support them)
        return test_cases

    def _test_pipe_additivity(
        self,
        client: OpenSearch,
        context: IndexContext,
        cmd_a: str,
        cmd_b: str,
        description: str
    ) -> Optional[PropertyViolation]:
        """Test if source=idx | A | B equals materialize(source=idx | A) | B"""

        # Query 1: Direct pipe (A | B)
        direct_query = f"source={context.name} | {cmd_a} | {cmd_b}"
        direct_result = self._execute_ppl(client, direct_query)

        # Query 2: Materialized pipe
        # Step 2a: Run A and get results with schema
        intermediate_query = f"source={context.name} | {cmd_a}"
        intermediate_response = self._execute_ppl_full(client, intermediate_query)
        intermediate_result = intermediate_response.get('datarows', [])
        intermediate_schema = intermediate_response.get('schema', [])

        # Step 2b: Materialize intermediate results to temp index
        temp_result = self._create_temp_index(
            client, context, intermediate_result, intermediate_schema, cmd_a
        )

        if temp_result is None:
            # Couldn't materialize (e.g., no results or schema inference failed)
            return None

        temp_index_name, field_names = temp_result

        try:
            # Step 2c: Run B on materialized index
            # Project fields in the correct order first to match the intermediate result structure
            fields_projection = ', '.join(field_names)
            materialized_query = f"source={temp_index_name} | fields {fields_projection} | {cmd_b}"
            materialized_result = self._execute_ppl(client, materialized_query)

            # Compare results
            if not self._results_equal(direct_result, materialized_result):
                return PropertyViolation(
                    property_name=self.name,
                    query=f"Direct: {direct_query}\nMaterialized: {intermediate_query} -> {materialized_query}",
                    expected=f"{len(direct_result)} rows: {direct_result[:3]}",
                    actual=f"{len(materialized_result)} rows: {materialized_result[:3]}",
                    message=f"Additive pipe property violated for {description}: "
                            f"direct pipe != materialized pipe"
                )

        finally:
            # Clean up temp index
            if client.indices.exists(index=temp_index_name):
                client.indices.delete(index=temp_index_name)

        return None

    def _create_temp_index(
        self,
        client: OpenSearch,
        original_context: IndexContext,
        intermediate_result: List[Any],
        intermediate_schema: List[Dict[str, str]],
        cmd_a: str
    ) -> Optional[tuple]:
        """Create a temporary index with intermediate query results

        Returns:
            tuple of (index_name, field_names) or None if materialization failed
        """

        if not intermediate_result:
            return None

        # Generate unique temp index name based on command hash
        hash_suffix = hashlib.md5(cmd_a.encode()).hexdigest()[:8]
        temp_index_name = f"temp_pipe_{hash_suffix}"

        # Delete if exists
        if client.indices.exists(index=temp_index_name):
            client.indices.delete(index=temp_index_name)

        # Build mapping from schema
        # Map PPL types to OpenSearch types
        # Note: PPL sometimes returns different type names than OpenSearch mappings
        type_mapping = {
            'integer': 'integer',
            'long': 'long',
            'float': 'float',
            'double': 'double',
            'boolean': 'boolean',
            'string': 'keyword',
            'text': 'text',
            'keyword': 'keyword',
            'date': 'date',
            'timestamp': 'date',
            'ip': 'ip',
            # Aggregation result types
            'count': 'long',  # count() returns long
            'sum': 'double',  # sum() returns double
            'avg': 'double',
            'min': 'double',
            'max': 'double'
        }

        properties = {}
        field_names = []

        for col_idx, col_schema in enumerate(intermediate_schema):
            field_name_raw = col_schema.get('name', f"col{col_idx}")
            # Sanitize field name - remove parentheses and special chars that might cause issues
            field_name = field_name_raw.replace('()', '').replace('(', '_').replace(')', '').replace(' ', '_')
            if not field_name or field_name[0].isdigit():
                field_name = f"col{col_idx}"

            field_type = col_schema.get('type', 'keyword').lower()

            # Map to OpenSearch type
            os_type = type_mapping.get(field_type, 'keyword')

            # Also infer type from actual data if available (check multiple rows for better accuracy)
            if intermediate_result and len(intermediate_result) > 0:
                # Check up to 3 non-null values to infer type
                sample_values = []
                for row in intermediate_result[:min(5, len(intermediate_result))]:
                    if col_idx < len(row) and row[col_idx] is not None:
                        sample_values.append(row[col_idx])
                    if len(sample_values) >= 3:
                        break

                if sample_values:
                    sample_value = sample_values[0]
                    # Check if all samples have consistent types
                    if isinstance(sample_value, bool):
                        os_type = 'boolean'
                    elif isinstance(sample_value, int) and not isinstance(sample_value, bool):
                        # Verify other samples are also int
                        if all(isinstance(v, int) and not isinstance(v, bool) for v in sample_values):
                            os_type = 'long'
                    elif isinstance(sample_value, float):
                        os_type = 'double'
                    elif isinstance(sample_value, str):
                        # For strings, prefer integer if they look like numbers
                        # This handles cases where PPL schema says "string" but values are numeric strings
                        try:
                            int(sample_value)
                            # Check if all samples are numeric strings
                            if all(v is None or (isinstance(v, str) and v.lstrip('-').isdigit()) for v in sample_values):
                                os_type = 'long'  # Store as long so queries work correctly
                            else:
                                os_type = 'keyword'
                        except (ValueError, AttributeError):
                            os_type = 'keyword'

            # Handle nested field paths (e.g., "obj.nested.value")
            # We need to create nested structure in properties
            if '.' in field_name:
                parts = field_name.split('.')
                current = properties
                for part in parts[:-1]:
                    if part not in current:
                        current[part] = {"properties": {}}
                    if "properties" not in current[part]:
                        current[part]["properties"] = {}
                    current = current[part]["properties"]
                # Set the leaf field type
                current[parts[-1]] = {"type": os_type}
            else:
                properties[field_name] = {"type": os_type}

            field_names.append(field_name)

        mapping = {
            "mappings": {
                "properties": properties,
                "dynamic": "true"  # Allow additional fields
            }
        }

        client.indices.create(index=temp_index_name, body=mapping)

        # Insert documents using field names from schema
        documents = []
        for i, row in enumerate(intermediate_result):
            source = {}
            for col_idx, value in enumerate(row):
                if col_idx < len(field_names):
                    field_name = field_names[col_idx]
                    # Handle nested field names (e.g., "obj.nested.value")
                    # Need to create nested structure in the document
                    if '.' in field_name:
                        parts = field_name.split('.')
                        current = source
                        for part in parts[:-1]:
                            if part not in current:
                                current[part] = {}
                            current = current[part]
                        current[parts[-1]] = value
                    else:
                        source[field_name] = value
                else:
                    # Fallback for extra columns
                    source[f"col{col_idx}"] = value

            documents.append({
                "_index": temp_index_name,
                "_id": i,
                "_source": source
            })

        # Use bulk insert with error handling
        success_count, failed_items = bulk(client, documents, raise_on_error=False, stats_only=False)

        # If too many documents failed to index, skip this test
        failed_count = len(failed_items) if isinstance(failed_items, list) else failed_items
        if failed_count > len(documents) * 0.5:  # More than 50% failed
            # Clean up and return None to skip test
            if client.indices.exists(index=temp_index_name):
                client.indices.delete(index=temp_index_name)
            return None

        client.indices.refresh(index=temp_index_name)

        return (temp_index_name, field_names)

    def _results_equal(self, result1: List[Any], result2: List[Any]) -> bool:
        """Compare two result sets for equality"""

        if len(result1) != len(result2):
            return False

        # For exact comparison, results should match row by row
        # However, some commands might not guarantee order (e.g., stats without sort)
        # We'll do both ordered and unordered comparison

        # Try ordered comparison first
        try:
            if result1 == result2:
                return True
        except Exception:
            pass  # Continue to other methods

        # Try unordered comparison (convert to sets of tuples)
        # This handles cases where order might differ
        try:
            # Convert rows to tuples for hashing
            set1 = {self._row_to_tuple(row) for row in result1}
            set2 = {self._row_to_tuple(row) for row in result2}
            return set1 == set2
        except (TypeError, RecursionError):
            pass  # Fall through to next method

        # Try sorted comparison using string representation
        try:
            sorted1 = sorted(result1, key=lambda r: str(r))
            sorted2 = sorted(result2, key=lambda r: str(r))
            # Compare stringified versions to avoid type comparison issues
            return [str(r) for r in sorted1] == [str(r) for r in sorted2]
        except Exception:
            pass  # Fall through

        # Last resort: compare element by element with string conversion
        for r1, r2 in zip(result1, result2):
            if str(r1) != str(r2):
                return False
        return True

    def _row_to_tuple(self, row: Any) -> tuple:
        """Convert a row to a hashable tuple, handling None, float, and list values"""
        if not isinstance(row, (list, tuple)):
            row = [row]

        # Convert values to hashable types
        result = []
        for val in row:
            if isinstance(val, float):
                # Round to 6 decimal places to handle floating point imprecision
                result.append(round(val, 6))
            elif isinstance(val, list):
                # Convert lists to tuples (recursively)
                result.append(self._row_to_tuple(val))
            elif isinstance(val, dict):
                # Convert dicts to sorted tuple of items
                result.append(tuple(sorted(val.items())))
            else:
                result.append(val)
        return tuple(result)

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        """Execute a PPL query and return datarows"""
        response = self._execute_ppl_full(client, query)
        return response.get('datarows', [])

    def _execute_ppl_full(self, client: OpenSearch, query: str) -> Dict[str, Any]:
        """Execute a PPL query and return full response including schema"""
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response
