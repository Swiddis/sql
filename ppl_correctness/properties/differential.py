"""
Differential testing properties.

Compare semantically equivalent query forms:
- Sequential WHERE vs combined AND
- EVAL then WHERE vs WHERE with expression
- Different aggregation grouping orders
"""

from typing import Any, List
import random
from opensearchpy import OpenSearch

from ppl_correctness.datagen.context import IndexContext
from ppl_correctness.generators.ppl import PPLQueryGenerator
from ppl_correctness.properties.base import Property, PropertyViolation


class WhereConjunctionEquivalence(Property):
    """Verify: WHERE a | WHERE b === WHERE a AND b"""

    @property
    def name(self) -> str:
        return "WhereConjunctionEquivalence"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        if len(context.get_comparable_fields()) < 2:
            return violations

        rng = random.Random()
        generator = PPLQueryGenerator(context, rng)

        pred1 = generator.generate_predicate()
        pred2 = generator.generate_predicate()

        base = f"source={context.name}"
        query_sequential = f"{base} | where {pred1} | where {pred2} | fields *"
        query_combined = f"{base} | where ({pred1}) AND ({pred2}) | fields *"

        try:
            result_sequential = self._execute_ppl(client, query_sequential)
            result_combined = self._execute_ppl(client, query_combined)

            count_seq = len(result_sequential)
            count_comb = len(result_combined)

            if count_seq != count_comb:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=f"Sequential: {query_sequential}\nCombined: {query_combined}",
                    expected=count_seq,
                    actual=count_comb,
                    message=f"WHERE conjunction mismatch: sequential={count_seq}, combined={count_comb}"
                ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=f"{query_sequential} vs {query_combined}",
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


class EvalWhereEquivalence(Property):
    """Verify: EVAL x=expr | WHERE x>val === WHERE expr>val"""

    @property
    def name(self) -> str:
        return "EvalWhereEquivalence"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        numeric = context.get_numeric_fields()
        if len(numeric) < 2:
            return violations

        rng = random.Random()
        f1, f2 = rng.sample(numeric, 2)
        op = rng.choice(['+', '-', '*'])
        threshold = rng.randint(0, 100)

        base = f"source={context.name}"
        query_eval = f"{base} | eval tmp = {f1.name} {op} {f2.name} | where tmp > {threshold} | fields *"
        query_direct = f"{base} | where ({f1.name} {op} {f2.name}) > {threshold} | fields *"

        try:
            result_eval = self._execute_ppl(client, query_eval)
            result_direct = self._execute_ppl(client, query_direct)

            count_eval = len(result_eval)
            count_direct = len(result_direct)

            if count_eval != count_direct:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=f"EVAL: {query_eval}\nDirect: {query_direct}",
                    expected=count_eval,
                    actual=count_direct,
                    message=f"EVAL+WHERE != direct WHERE: eval={count_eval}, direct={count_direct}"
                ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=f"{query_eval} vs {query_direct}",
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
