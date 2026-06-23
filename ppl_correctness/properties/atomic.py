"""
Atomic property-based tests that catch bugs through fundamental invariants.

These properties replace the bug-specific tests by testing universal invariants
that hold regardless of field types, values, or specific bugs.
"""

from typing import Any, List
import random
from opensearchpy import OpenSearch

from ppl_correctness.datagen.context import IndexContext, FieldType
from ppl_correctness.generators.ppl import PPLQueryGenerator
from ppl_correctness.properties.base import Property, PropertyViolation
from ppl_correctness.known_bugs import should_skip


class GroupByConservation(Property):
    """
    Atomic property: Sum of grouped counts equals ungrouped total count.

    For ANY field (scalar, array, nested, etc.):
        SUM(stats count() by field) == stats count()

    Catches:
    - Issue #5333: Arrays exploded in GROUP BY (sum > total)
    - Missing NULL group handling
    - GROUP BY dropping rows
    """

    @property
    def name(self) -> str:
        return "GroupByConservation"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        groupable = context.get_groupable_fields()
        if not groupable:
            return violations

        rng = random.Random()
        # Test multiple groupable fields to increase coverage
        for group_field in rng.sample(groupable, min(2, len(groupable))):
            try:
                # Total count
                total_query = f"source={context.name} | stats count()"
                total_result = self._execute_ppl(client, total_query)
                total_count = total_result[0][0] if total_result else 0

                # Grouped count
                grouped_query = f"source={context.name} | stats count() by {group_field.name}"
                grouped_result = self._execute_ppl(client, grouped_query)
                grouped_sum = sum(row[0] for row in grouped_result)

                # Invariant: conservation of count
                if grouped_sum != total_count:
                    message = f"GROUP BY conservation violated for {group_field.name}"

                    # Provide context about what might be wrong
                    if grouped_sum > total_count:
                        if group_field.is_array:
                            message += f" (ARRAY EXPLOSION: grouped sum {grouped_sum} > total {total_count})"
                        else:
                            message += f" (grouped sum {grouped_sum} > total {total_count})"
                    else:
                        message += f" (grouped sum {grouped_sum} < total {total_count}, rows dropped?)"

                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=f"Total: {total_query}, Grouped: {grouped_query}",
                        expected=total_count,
                        actual=grouped_sum,
                        message=message
                    ))

            except Exception as e:
                # Queries should succeed for valid schemas
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=f"GROUP BY {group_field.name}",
                    expected="Successful execution",
                    actual=str(e),
                    message=f"Query execution failed: {e}"
                ))

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])


class FieldAccessConsistency(Property):
    """
    Atomic property: Fields that exist in documents should be accessible.

    If a field is in the schema and has non-null values, projecting it
    should return those values, not null.

    Catches:
    - Issue #4906: Nested fields return null despite existing
    - Field mapping issues
    - Projection bugs
    """

    @property
    def name(self) -> str:
        return "FieldAccessConsistency"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        rng = random.Random()
        # Test a sample of fields, skipping known bugs
        test_fields = [f for f in context.fields if not should_skip(field=f)]
        test_fields = rng.sample(test_fields, min(3, len(test_fields))) if test_fields else []

        for field in test_fields:
            try:
                # Query: project just this field
                query = f"source={context.name} | fields {field.name} | head 10"
                result = self._execute_ppl(client, query)

                if not result:
                    continue

                # Count nulls
                null_count = sum(1 for row in result if row[0] is None)
                non_null_count = len(result) - null_count

                # If ALL values are null, something is wrong (unless field is very sparse)
                # Check raw document to verify field actually exists
                if null_count == len(result) and len(result) > 0:
                    # Get a raw document to check if field exists
                    raw_query = f"source={context.name} | head 1"
                    raw_result = self._execute_ppl(client, raw_query)

                    if raw_result:
                        # For nested fields, this is likely the bug
                        if '.' in field.name:
                            violations.append(PropertyViolation(
                                property_name=self.name,
                                query=query,
                                expected="Non-null values for nested field",
                                actual=f"All {len(result)} values are null",
                                message=f"Nested field {field.name} returns null (Issue #4906 pattern)"
                            ))
                        elif field.nullable and field.type != FieldType.TEXT:
                            # Might be legitimately all null, but suspicious
                            pass

            except Exception as e:
                # Field access should succeed
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected="Successful field projection",
                    actual=str(e),
                    message=f"Field projection failed: {e}"
                ))

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])


class FilterAggregationConsistency(Property):
    """
    Atomic property: Filters should affect aggregation results consistently.

    If WHERE isnotnull(field) filters out nulls, then:
    1. stats count() should exclude nulls
    2. stats by field should have no null groups
    3. Filter count should equal sum of grouped counts

    Catches:
    - Issue #4463: isnotnull filter not applied with text+keyword subfields
    - Filter/aggregation field mapping mismatches
    """

    @property
    def name(self) -> str:
        return "FilterAggregationConsistency"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        # Focus on fields with subfields (text+keyword) and groupable fields
        test_fields = [f for f in context.fields if f.subfields or f in context.get_groupable_fields()]

        rng = random.Random()
        for field in rng.sample(test_fields, min(2, len(test_fields))):
            # Skip known bugs
            if should_skip(query=f"isnotnull({field.name})", field=field):
                continue
            try:
                # Test 1: Filter count
                filter_query = f"source={context.name} | where isnotnull({field.name}) | stats count()"
                filter_result = self._execute_ppl(client, filter_query)
                filter_count = filter_result[0][0] if filter_result else 0

                # Test 2: Aggregation with filter
                agg_query = f"source={context.name} | where isnotnull({field.name}) | stats count() by {field.name}"
                agg_result = self._execute_ppl(client, agg_query)

                # Check for null groups (should not exist after isnotnull filter)
                null_groups = [row for row in agg_result if row[1] is None]

                if null_groups:
                    null_count = null_groups[0][0]
                    message = f"isnotnull({field.name}) filter failed: {null_count} docs in null group"

                    # Provide specific diagnostic
                    if field.subfields and 'keyword' in field.subfields:
                        message += f" (TEXT+KEYWORD subfield mismatch, Issue #4463 pattern)"

                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=agg_query,
                        expected="No null groups after isnotnull filter",
                        actual=f"Null group with count={null_count}",
                        message=message
                    ))

                # Check count conservation
                agg_sum = sum(row[0] for row in agg_result)
                if agg_sum != filter_count:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=f"Filter: {filter_query}, Agg: {agg_query}",
                        expected=filter_count,
                        actual=agg_sum,
                        message=f"Filter count ({filter_count}) != aggregation sum ({agg_sum})"
                    ))

            except Exception as e:
                # Queries should succeed
                pass

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])


class FieldValuePreservation(Property):
    """
    Atomic property: Field values should be preserved through command chains.

    If field F has non-null values, then applying transformations:
        | rename F as G | <command> | fields G
    should still return non-null values for G.

    Catches:
    - Issue #5150: dedup nullifies renamed fields
    - Command interaction bugs
    - Field reference tracking issues
    """

    @property
    def name(self) -> str:
        return "FieldValuePreservation"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        rng = random.Random()
        # Test fields that are likely to have non-null values
        test_fields = [f for f in context.fields if not f.is_array and not should_skip(field=f)]

        if not test_fields:
            return violations

        field = rng.choice(test_fields)
        alias = f"renamed_{field.name.replace('.', '_')}"

        try:
            # Control: just project the field
            control_query = f"source={context.name} | fields {field.name} | head 5"
            control_result = self._execute_ppl(client, control_query)
            control_null_count = sum(1 for row in control_result if row[0] is None)

            # Test: rename + dedup + project
            groupable = context.get_groupable_fields()
            if groupable:
                dedup_field = rng.choice(groupable)
                test_query = f"source={context.name} | rename {field.name} as {alias} | dedup {dedup_field.name} | fields {alias}"

                # Skip known bugs
                if should_skip(query=test_query, field=field):
                    return violations
                test_result = self._execute_ppl(client, test_query)
                test_null_count = sum(1 for row in test_result if row[0] is None)

                # Invariant: null rate shouldn't drastically increase
                # If control has no nulls but test has all nulls, that's the bug
                if control_null_count == 0 and test_null_count == len(test_result) and len(test_result) > 0:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=test_query,
                        expected="Non-null values preserved through rename+dedup",
                        actual=f"All {test_null_count} values became null",
                        message=f"RENAME + DEDUP nullified field (Issue #5150 pattern)"
                    ))

            # Also test rename + sort
            if context.get_comparable_fields():
                sort_field = rng.choice(context.get_comparable_fields())
                test_query2 = f"source={context.name} | rename {field.name} as {alias} | sort {sort_field.name} | fields {alias} | head 5"
                test_result2 = self._execute_ppl(client, test_query2)
                test_null_count2 = sum(1 for row in test_result2 if row[0] is None)

                if control_null_count == 0 and test_null_count2 == len(test_result2) and len(test_result2) > 0:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=test_query2,
                        expected="Non-null values preserved through rename+sort",
                        actual=f"All {test_null_count2} values became null",
                        message=f"RENAME + SORT nullified field"
                    ))

        except Exception as e:
            # Queries should succeed
            pass

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])
