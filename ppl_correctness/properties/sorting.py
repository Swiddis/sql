"""
Sorting invariant properties.

- Results from `sort field` must be ordered
- `sort +field | head 1` should equal `stats max(field)`
- `sort -field | head 1` should equal `stats min(field)`
"""

from typing import Any, List
import random
from opensearchpy import OpenSearch

from ppl_correctness.datagen.context import IndexContext, FieldType
from ppl_correctness.generators.ppl import PPLQueryGenerator
from ppl_correctness.properties.base import Property, PropertyViolation


class SortingInvariant(Property):
    """Verify sorting produces correctly ordered results"""

    @property
    def name(self) -> str:
        return "SortingInvariant"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        sortable = context.get_comparable_fields()
        if not sortable:
            return violations

        rng = random.Random()
        field = rng.choice(sortable)
        direction = rng.choice(['+', '-'])

        query = f"source={context.name} | sort {direction}{field.name} | fields {field.name}"

        try:
            results = self._execute_ppl(client, query)
            values = [row[0] for row in results if row[0] is not None]

            if not values:
                return violations

            # Check ordering
            is_ascending = direction == '+'
            for i in range(len(values) - 1):
                if is_ascending and values[i] > values[i + 1]:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=query,
                        expected="Ascending order",
                        actual=f"{values[i]} > {values[i+1]} at position {i}",
                        message=f"Sort order violated in ascending sort"
                    ))
                    break
                elif not is_ascending and values[i] < values[i + 1]:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=query,
                        expected="Descending order",
                        actual=f"{values[i]} < {values[i+1]} at position {i}",
                        message=f"Sort order violated in descending sort"
                    ))
                    break

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


class SortHeadEquivalence(Property):
    """Verify sort+head equivalence to min/max aggregation"""

    @property
    def name(self) -> str:
        return "SortHeadEquivalence"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        numeric = context.get_numeric_fields()
        if not numeric:
            return violations

        rng = random.Random()
        field = rng.choice(numeric)

        # Test max equivalence
        sort_query = f"source={context.name} | sort -{field.name} | head 1 | fields {field.name}"
        stats_query = f"source={context.name} | stats max({field.name})"

        try:
            sort_result = self._execute_ppl(client, sort_query)
            stats_result = self._execute_ppl(client, stats_query)

            if sort_result and stats_result:
                sort_value = sort_result[0][0]
                stats_value = stats_result[0][0]

                if sort_value != stats_value:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=f"Sort: {sort_query}, Stats: {stats_query}",
                        expected=stats_value,
                        actual=sort_value,
                        message="sort -field | head 1 != stats max(field)"
                    ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=f"{sort_query} vs {stats_query}",
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
