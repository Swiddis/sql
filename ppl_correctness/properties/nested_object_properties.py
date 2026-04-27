"""
Property-based tests for nested object field handling.

These properties would catch Issue #4906 (nested JSON object access returns null).
"""

from typing import List, Any
import random
from opensearchpy import OpenSearch
from opensearchpy.helpers import bulk

from ppl_correctness.datagen.context import IndexContext, Field, FieldType
from ppl_correctness.properties.base import Property, PropertyViolation


def create_nested_context(client: OpenSearch, seed: int = 42) -> IndexContext:
    """Create test context with nested object fields"""
    rng = random.Random(seed)
    index_name = "test_nested_properties"

    if client.indices.exists(index=index_name):
        client.indices.delete(index=index_name)

    # Create index with nested objects (enabled=false to match bug report)
    mapping = {
        "mappings": {
            "properties": {
                "id": {"type": "integer"},
                "flat_value": {"type": "integer"},
                "nested_obj": {
                    "type": "object",
                    "enabled": False  # Key condition for bug #4906
                },
                "normal_nested": {
                    "type": "object",
                    "properties": {
                        "value": {"type": "integer"}
                    }
                }
            }
        }
    }
    client.indices.create(index=index_name, body=mapping)

    # Insert documents
    documents = []
    for i in range(20):
        doc = {
            "_index": index_name,
            "_id": i,
            "_source": {
                "id": i,
                "flat_value": rng.randint(0, 100),
                "nested_obj": {
                    "a": rng.randint(0, 10),
                    "c": {
                        "d": rng.randint(0, 10)
                    }
                },
                "normal_nested": {
                    "value": rng.randint(0, 100)
                }
            }
        }
        documents.append(doc)

    bulk(client, documents)
    client.indices.refresh(index=index_name)

    return IndexContext(
        name=index_name,
        fields=[
            Field("id", FieldType.INTEGER),
            Field("flat_value", FieldType.INTEGER),
            Field("nested_obj.a", FieldType.INTEGER),
            Field("nested_obj.c.d", FieldType.INTEGER),
            Field("normal_nested.value", FieldType.INTEGER),
        ],
        doc_count=20
    )


class NestedFieldAccessNullCheck(Property):
    """
    Test: Accessing nested object fields should return values, not null.

    Property: If a field exists with value V in the source document,
    querying that field should return V, not null.

    This catches Issue #4906: nested_obj.c.d returns null even though it exists.
    """

    @property
    def name(self) -> str:
        return "NestedFieldAccessNullCheck"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        nested_context = create_nested_context(client)

        try:
            # Test accessing deeply nested field (the bug case)
            query_disabled = f"source={nested_context.name} | fields nested_obj.c.d | head 1"
            result_disabled = self._execute_ppl(client, query_disabled)

            # Test accessing normal nested field (control case)
            query_normal = f"source={nested_context.name} | fields normal_nested.value | head 1"
            result_normal = self._execute_ppl(client, query_normal)

            # Property: Values should not be null if they exist
            if result_disabled and result_disabled[0][0] is None:
                # Check if the field actually exists in raw document
                raw_query = f"source={nested_context.name} | fields nested_obj | head 1"
                raw_result = self._execute_ppl(client, raw_query)

                if raw_result:  # Document exists
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=query_disabled,
                        expected="Non-null value for nested_obj.c.d",
                        actual="null",
                        message="Accessing nested field (enabled=false) returns null despite field existing. "
                                "This is Issue #4906."
                    ))

            # Verify normal nested field works (sanity check)
            if result_normal and result_normal[0][0] is None:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query_normal,
                    expected="Non-null value for normal_nested.value",
                    actual="null",
                    message="Normal nested field also returns null - broader issue than #4906"
                ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=f"Nested field access test",
                expected="Successful execution",
                actual=str(e),
                message=f"Query failed: {e}"
            ))
        finally:
            if client.indices.exists(index=nested_context.name):
                client.indices.delete(index=nested_context.name)

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])


class NestedFieldFilterConsistency(Property):
    """
    Test: Filtering by nested fields should be consistent with field projection.

    Property: If WHERE nested.field = X returns N rows,
    then FIELDS nested.field on those N rows should show X.

    This tests consistency of nested field access across operations.
    """

    @property
    def name(self) -> str:
        return "NestedFieldFilterConsistency"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        nested_context = create_nested_context(client)

        try:
            # Test: WHERE normal_nested.value > 50 | fields normal_nested.value
            query = f"source={nested_context.name} | where normal_nested.value > 50 | fields normal_nested.value"
            result = self._execute_ppl(client, query)

            # Property: All returned values should be > 50
            invalid_values = [row[0] for row in result if row[0] is not None and row[0] <= 50]

            if invalid_values:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected="All values > 50",
                    actual=f"Found values <= 50: {invalid_values}",
                    message="WHERE nested.value > 50 returns docs with value <= 50"
                ))

            # Property: No null values if WHERE filtered for non-null
            null_values = [row for row in result if row[0] is None]

            if null_values:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected="No null values after WHERE comparison",
                    actual=f"{len(null_values)} null values found",
                    message="Nested field returns null after non-null WHERE filter"
                ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=query,
                expected="Successful execution",
                actual=str(e),
                message=f"Query failed: {e}"
            ))
        finally:
            if client.indices.exists(index=nested_context.name):
                client.indices.delete(index=nested_context.name)

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])
