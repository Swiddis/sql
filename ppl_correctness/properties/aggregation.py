"""
Aggregation invariant properties.

- Sum of grouped counts equals total count
- Sum of grouped sums equals total sum
- Grouped aggregations maintain monotonicity (max >= min)
"""

from typing import Any, List
import random
from opensearchpy import OpenSearch

from ppl_correctness.datagen.context import IndexContext
from ppl_correctness.properties.base import Property, PropertyViolation


class AggregationConservation(Property):
    """Verify grouped aggregations sum to total aggregation"""

    @property
    def name(self) -> str:
        return "AggregationConservation"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        groupable = context.get_groupable_fields()
        if not groupable:
            return violations

        rng = random.Random()
        group_field = rng.choice(groupable)

        # Test count conservation
        total_query = f"source={context.name} | stats count()"
        grouped_query = f"source={context.name} | stats count() by {group_field.name}"

        try:
            total_result = self._execute_ppl(client, total_query)
            grouped_result = self._execute_ppl(client, grouped_query)

            if total_result and grouped_result:
                total_count = total_result[0][0]
                grouped_sum = sum(row[0] for row in grouped_result)

                if total_count != grouped_sum:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=f"Total: {total_query}, Grouped: {grouped_query}",
                        expected=total_count,
                        actual=grouped_sum,
                        message=f"Grouped count sum ({grouped_sum}) != total count ({total_count})"
                    ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=f"{total_query} vs {grouped_query}",
                expected="Successful execution",
                actual=str(e),
                message=f"Query failed: {e}"
            ))

        # Test sum conservation for numeric fields
        numeric = context.get_numeric_fields()
        if numeric:
            field = rng.choice(numeric)
            total_query = f"source={context.name} | stats sum({field.name})"
            grouped_query = f"source={context.name} | stats sum({field.name}) by {group_field.name}"

            try:
                total_result = self._execute_ppl(client, total_query)
                grouped_result = self._execute_ppl(client, grouped_query)

                if total_result and grouped_result:
                    total_sum = total_result[0][0] or 0
                    grouped_sum = sum(row[0] or 0 for row in grouped_result)

                    # Allow small floating point tolerance
                    tolerance = abs(total_sum) * 0.0001 + 0.0001
                    if abs(total_sum - grouped_sum) > tolerance:
                        violations.append(PropertyViolation(
                            property_name=self.name,
                            query=f"Total: {total_query}, Grouped: {grouped_query}",
                            expected=total_sum,
                            actual=grouped_sum,
                            message=f"Grouped sum conservation violated: {grouped_sum} != {total_sum}"
                        ))

            except Exception as e:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=f"{total_query} vs {grouped_query}",
                    expected="Successful execution",
                    actual=str(e),
                    message=f"Query failed: {e}"
                ))

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])


class MonotonicityInvariant(Property):
    """Verify max >= min for all aggregations"""

    @property
    def name(self) -> str:
        return "MonotonicityInvariant"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        numeric = context.get_numeric_fields()
        if not numeric:
            return violations

        rng = random.Random()
        field = rng.choice(numeric)

        query = f"source={context.name} | stats min({field.name}), max({field.name})"

        try:
            result = self._execute_ppl(client, query)

            if result:
                min_val, max_val = result[0]

                if min_val is not None and max_val is not None and min_val > max_val:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=query,
                        expected="min <= max",
                        actual=f"min={min_val}, max={max_val}",
                        message=f"Monotonicity violated: min ({min_val}) > max ({max_val})"
                    ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=query,
                expected="Successful execution",
                actual=str(e),
                message=f"Query failed: {e}"
            ))

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])
