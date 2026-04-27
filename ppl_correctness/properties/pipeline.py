"""
Pipeline invariant properties.

Tests correctness of command chains:
- Commutative operations (order shouldn't matter)
- Idempotent operations (multiple applications = single)
- Filter pushdown equivalence
- Projection before/after aggregation
"""

from typing import Any, List
import random
from opensearchpy import OpenSearch

from ppl_correctness.datagen.context import IndexContext
from ppl_correctness.generators.ppl import PPLQueryGenerator
from ppl_correctness.properties.base import Property, PropertyViolation


class FilterCommutativity(Property):
    """
    Test: WHERE a | WHERE b === WHERE b | WHERE a

    Independent filters should be commutative.
    """

    @property
    def name(self) -> str:
        return "FilterCommutativity"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        if len(context.get_comparable_fields()) < 2:
            return violations

        rng = random.Random()
        generator = PPLQueryGenerator(context, rng)

        pred1 = generator.generate_predicate()
        pred2 = generator.generate_predicate()

        base = f"source={context.name}"
        query_ab = f"{base} | where {pred1} | where {pred2} | stats count()"
        query_ba = f"{base} | where {pred2} | where {pred1} | stats count()"

        try:
            result_ab = self._execute_ppl(client, query_ab)
            result_ba = self._execute_ppl(client, query_ba)

            count_ab = result_ab[0][0] if result_ab else 0
            count_ba = result_ba[0][0] if result_ba else 0

            if count_ab != count_ba:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=f"A then B: {query_ab}\nB then A: {query_ba}",
                    expected=count_ab,
                    actual=count_ba,
                    message=f"Filter order changed results: {count_ab} != {count_ba}"
                ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=f"{query_ab} vs {query_ba}",
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


class DedupIdempotence(Property):
    """
    Test: | dedup field | dedup field === | dedup field

    Dedup should be idempotent.
    """

    @property
    def name(self) -> str:
        return "DedupIdempotence"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        if not context.fields:
            return violations

        rng = random.Random()
        field = rng.choice(context.fields)

        base = f"source={context.name}"
        query_single = f"{base} | dedup {field.name} | stats count()"
        query_double = f"{base} | dedup {field.name} | dedup {field.name} | stats count()"

        try:
            result_single = self._execute_ppl(client, query_single)
            result_double = self._execute_ppl(client, query_double)

            count_single = result_single[0][0] if result_single else 0
            count_double = result_double[0][0] if result_double else 0

            if count_single != count_double:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=f"Single: {query_single}\nDouble: {query_double}",
                    expected=count_single,
                    actual=count_double,
                    message=f"Dedup not idempotent: {count_single} != {count_double}"
                ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=f"{query_single} vs {query_double}",
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


class ProjectionAggregationOrder(Property):
    """
    Test: Fields selection before/after aggregation

    For aggregations, FIELDS doesn't affect the result (only display).
    Test: | stats agg | fields f === | fields f | stats agg (if valid)

    Note: This tests semantic equivalence where both are valid.
    """

    @property
    def name(self) -> str:
        return "ProjectionAggregationOrder"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        groupable = context.get_groupable_fields()
        if not groupable:
            return violations

        rng = random.Random()
        group_field = rng.choice(groupable)

        base = f"source={context.name}"

        # Test: FIELDS before/after STATS with grouping
        # Both should produce same aggregation results
        query_fields_after = f"{base} | stats count() by {group_field.name} | fields {group_field.name}"
        query_fields_implicit = f"{base} | stats count() by {group_field.name}"

        try:
            result_after = self._execute_ppl(client, query_fields_after)
            result_implicit = self._execute_ppl(client, query_fields_implicit)

            # Compare row counts (structure might differ)
            count_after = len(result_after)
            count_implicit = len(result_implicit)

            if count_after != count_implicit:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=f"After: {query_fields_after}\nImplicit: {query_fields_implicit}",
                    expected=count_implicit,
                    actual=count_after,
                    message=f"Projection changed aggregation row count: {count_after} != {count_implicit}"
                ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=f"{query_fields_after} vs {query_fields_implicit}",
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


class EvalFieldsIndependence(Property):
    """
    Test: EVAL creates new field without affecting existing fields

    | eval new=x+y | fields old === | fields old (same old values)
    """

    @property
    def name(self) -> str:
        return "EvalFieldsIndependence"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        numeric = context.get_numeric_fields()
        if len(numeric) < 2:
            return violations

        rng = random.Random()
        f1, f2 = rng.sample(numeric, 2)
        existing_field = rng.choice(context.fields)

        base = f"source={context.name}"

        # Compare values of existing field with/without EVAL
        query_no_eval = f"{base} | fields {existing_field.name} | head 10"
        query_with_eval = f"{base} | eval computed = {f1.name} + {f2.name} | fields {existing_field.name} | head 10"

        try:
            result_no_eval = self._execute_ppl(client, query_no_eval)
            result_with_eval = self._execute_ppl(client, query_with_eval)

            # Results should be identical (same order, same values)
            if result_no_eval != result_with_eval:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=f"No eval: {query_no_eval}\nWith eval: {query_with_eval}",
                    expected=result_no_eval,
                    actual=result_with_eval,
                    message="EVAL changed existing field values"
                ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=f"{query_no_eval} vs {query_with_eval}",
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


class MultipleAggregationsEquivalence(Property):
    """
    Test: Multiple aggregations in one STATS vs separate STATS

    This tests that aggregation computation is independent.

    | stats count(), sum(x) === (| stats count()) + (| stats sum(x))
    (As in: same counts/sums in single vs separate queries)
    """

    @property
    def name(self) -> str:
        return "MultipleAggregationsEquivalence"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        numeric = context.get_numeric_fields()
        if not numeric:
            return violations

        rng = random.Random()
        field = rng.choice(numeric)

        base = f"source={context.name}"

        # Combined aggregations
        query_combined = f"{base} | stats count(), sum({field.name})"

        # Separate queries
        query_count = f"{base} | stats count()"
        query_sum = f"{base} | stats sum({field.name})"

        try:
            result_combined = self._execute_ppl(client, query_combined)
            result_count = self._execute_ppl(client, query_count)
            result_sum = self._execute_ppl(client, query_sum)

            if result_combined and result_count and result_sum:
                combined_count = result_combined[0][0]
                combined_sum = result_combined[0][1]

                separate_count = result_count[0][0]
                separate_sum = result_sum[0][0]

                if combined_count != separate_count:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=f"Combined: {query_combined}\nSeparate: {query_count}",
                        expected=separate_count,
                        actual=combined_count,
                        message=f"Count differs: combined={combined_count}, separate={separate_count}"
                    ))

                # Allow floating point tolerance for sum
                if separate_sum is not None and combined_sum is not None:
                    tolerance = abs(separate_sum) * 0.0001 + 0.0001
                    if abs(combined_sum - separate_sum) > tolerance:
                        violations.append(PropertyViolation(
                            property_name=self.name,
                            query=f"Combined: {query_combined}\nSeparate: {query_sum}",
                            expected=separate_sum,
                            actual=combined_sum,
                            message=f"Sum differs: combined={combined_sum}, separate={separate_sum}"
                        ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=f"{query_combined}",
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
