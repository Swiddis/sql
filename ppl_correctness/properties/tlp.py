"""
Ternary Logic Partitioning (TLP) property.

For any query q and predicate p:
  UNION(q WHERE p, q WHERE NOT p, q WHERE p IS NULL) == q

This property holds because boolean logic has exactly three states: true, false, null.
"""

from gelidum import freeze
from typing import Any, List
import random
from opensearchpy import OpenSearch

from ppl_correctness.datagen.context import IndexContext
from ppl_correctness.generators.ppl import PPLQueryGenerator
from ppl_correctness.properties.base import Property, PropertyViolation


class TernaryLogicPartitioning(Property):
    """TLP: Partitioning by predicate should cover entire result set"""

    @property
    def name(self) -> str:
        return "TernaryLogicPartitioning"

    def check(
        self, context: IndexContext, client: OpenSearch
    ) -> List[PropertyViolation]:
        violations = []

        # Generate random predicate
        rng = random.Random()
        generator = PPLQueryGenerator(context, rng)

        base_query = generator.generate_base_query()
        predicate = generator.generate_predicate()

        # Execute three partitions
        query_true = f"{base_query} | where {predicate} | fields *"
        query_false = f"{base_query} | where NOT ({predicate}) | fields *"
        query_null = f"{base_query} | where isnull({predicate}) | fields *"

        try:
            result_true = self._execute_ppl(client, query_true)
            result_false = self._execute_ppl(client, query_false)
            result_null = self._execute_ppl(client, query_null)
            result_total = self._execute_ppl(client, f"{base_query} | fields *")

            # Count results
            count_union = len(result_true) + len(result_false) + len(result_null)
            count_total = len(result_total)

            if count_union != count_total:
                violations.append(
                    PropertyViolation(
                        property_name=self.name,
                        query=f"Predicate: {predicate}",
                        expected=count_total,
                        actual=count_union,
                        message=f"TLP partition count mismatch: {count_union} != {count_total}",
                    )
                )

            # Verify no duplicates across partitions (row uniqueness)
            ids_true = set(self._extract_ids(result_true))
            ids_false = set(self._extract_ids(result_false))
            ids_null = set(self._extract_ids(result_null))

            overlaps = (
                (ids_true & ids_false) | (ids_true & ids_null) | (ids_false & ids_null)
            )
            if overlaps:
                violations.append(
                    PropertyViolation(
                        property_name=self.name,
                        query=f"Predicate: {predicate}",
                        expected="Disjoint partitions",
                        actual=f"{len(overlaps)} overlapping rows",
                        message="TLP partitions overlap (same row in multiple partitions)",
                    )
                )

        except Exception as e:
            violations.append(
                PropertyViolation(
                    property_name=self.name,
                    query=f"Predicate: {predicate}",
                    expected="Successful execution",
                    actual=str(e),
                    message=f"Query execution failed: {e}",
                )
            )

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[dict]:
        """Execute PPL query and return results"""
        response = client.transport.perform_request(
            "POST", "/_plugins/_ppl", body={"query": query}
        )

        # Extract datarows from response
        if "datarows" in response:
            return response["datarows"]
        return []

    def _extract_ids(self, rows: List[Any]) -> List[Any]:
        """Extract unique identifiers from result rows"""
        # Assuming rows are tuples/lists, use entire row as ID
        return [freeze(row) for row in rows]
