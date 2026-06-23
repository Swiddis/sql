"""
Hypothesis-powered TLP property.

Demonstrates integration with Hypothesis for better shrinking and reporting.
"""

from typing import List
from opensearchpy import OpenSearch

from ppl_correctness.datagen.context import IndexContext
from ppl_correctness.properties.base import Property, PropertyViolation
from ppl_correctness.known_bugs import should_skip

try:
    from hypothesis import given, settings, assume, Phase
    from hypothesis import strategies as st
    from ppl_correctness.generators.strategies import predicates, test_scenario
    HYPOTHESIS_AVAILABLE = True
except ImportError:
    HYPOTHESIS_AVAILABLE = False


if HYPOTHESIS_AVAILABLE:
    class HypothesisTLP(Property):
        """
        TLP property using Hypothesis strategies.

        Benefits over manual randomization:
        - Automatic shrinking to minimal failing case
        - Better statistical distribution
        - Integrated failure reporting
        - Deterministic replay with examples
        """

        def __init__(self):
            self._violations = []

        @property
        def name(self) -> str:
            return "HypothesisTLP"

        def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
            """Run Hypothesis-driven TLP tests"""
            self._violations = []
            self._context = context
            self._client = client

            try:
                # Use Hypothesis to generate and test predicates
                self._run_hypothesis_test()
            except AssertionError as e:
                # Hypothesis found a failing case and shrunk it
                # Violations are already recorded in _violations
                pass

            return self._violations

        @settings(
            max_examples=50,  # Number of test cases
            phases=[Phase.generate, Phase.shrink],  # Enable shrinking
            deadline=None,  # Disable deadline for slow queries
        )
        @given(test_scenario=st.data())
        def _run_hypothesis_test(self, test_scenario):
            """Hypothesis test method"""
            # Generate a test scenario using strategies
            from ppl_correctness.generators.strategies import test_scenario as scenario_strategy

            scenario = test_scenario.draw(scenario_strategy(self._context))

            # Skip known bugs
            skip_reason = should_skip(query=scenario['query_with_pred'])
            assume(not skip_reason)

            # Execute TLP queries
            query_true = scenario['query_with_pred']
            query_false = scenario['query_negated']
            query_null = scenario['query_null']
            base_query = scenario['base'] + " | fields *"

            try:
                result_true = self._execute_ppl(self._client, query_true + " | fields *")
                result_false = self._execute_ppl(self._client, query_false + " | fields *")
                result_null = self._execute_ppl(self._client, query_null + " | fields *")
                result_total = self._execute_ppl(self._client, base_query)

                count_union = len(result_true) + len(result_false) + len(result_null)
                count_total = len(result_total)

                # Hypothesis will shrink the predicate if this fails
                if count_union != count_total:
                    violation = PropertyViolation(
                        property_name=self.name,
                        query=f"Predicate: {scenario['predicate']}",
                        expected=count_total,
                        actual=count_union,
                        message=f"TLP count mismatch: {count_union} != {count_total}"
                    )
                    self._violations.append(violation)
                    # Re-raise to trigger Hypothesis shrinking
                    raise AssertionError(violation.message)

                # Check disjointness
                ids_true = set(self._extract_ids(result_true))
                ids_false = set(self._extract_ids(result_false))
                ids_null = set(self._extract_ids(result_null))

                overlaps = (ids_true & ids_false) | (ids_true & ids_null) | (ids_false & ids_null)
                if overlaps:
                    violation = PropertyViolation(
                        property_name=self.name,
                        query=f"Predicate: {scenario['predicate']}",
                        expected="Disjoint partitions",
                        actual=f"{len(overlaps)} overlapping rows",
                        message="TLP partitions overlap"
                    )
                    self._violations.append(violation)
                    raise AssertionError(violation.message)

            except Exception as e:
                if isinstance(e, AssertionError):
                    raise  # Let Hypothesis handle it
                # Other errors (query execution failures)
                violation = PropertyViolation(
                    property_name=self.name,
                    query=f"Predicate: {scenario['predicate']}",
                    expected="Successful execution",
                    actual=str(e),
                    message=f"Query execution failed: {e}"
                )
                self._violations.append(violation)

        def _execute_ppl(self, client: OpenSearch, query: str) -> List:
            response = client.transport.perform_request(
                'POST',
                '/_plugins/_ppl',
                body={'query': query}
            )
            return response.get('datarows', [])

        def _extract_ids(self, rows: List) -> List:
            return [tuple(row) if isinstance(row, list) else row for row in rows]


    # Example of using Hypothesis directly in pytest
    class TestTLPWithHypothesis:
        """
        Example: Using Hypothesis in a pytest test.

        Run with: pytest ppl_correctness/properties/hypothesis_tlp.py
        """

        @given(st.data())
        @settings(max_examples=100)
        def test_tlp_property(self, data, opensearch_client, test_context):
            """
            Parameterized test using Hypothesis.

            Fixtures:
            - opensearch_client: OpenSearch client fixture
            - test_context: IndexContext fixture
            """
            from ppl_correctness.generators.strategies import test_scenario

            scenario = data.draw(test_scenario(test_context))

            # Execute queries
            query_true = scenario['query_with_pred'] + " | stats count()"
            query_false = scenario['query_negated'] + " | stats count()"
            query_null = scenario['query_null'] + " | stats count()"
            query_total = scenario['base'] + " | stats count()"

            # Execute and get counts
            count_true = self._get_count(opensearch_client, query_true)
            count_false = self._get_count(opensearch_client, query_false)
            count_null = self._get_count(opensearch_client, query_null)
            count_total = self._get_count(opensearch_client, query_total)

            # Assert TLP invariant
            # Hypothesis will automatically shrink failing predicates
            assert count_true + count_false + count_null == count_total, \
                f"TLP violated for predicate: {scenario['predicate']}"

        def _get_count(self, client, query):
            response = client.transport.perform_request(
                'POST',
                '/_plugins/_ppl',
                body={'query': query}
            )
            return response['datarows'][0][0] if response.get('datarows') else 0


else:
    # Hypothesis not available - provide stub
    class HypothesisTLP:
        def __init__(self):
            raise ImportError(
                "Hypothesis is required for this property. "
                "Install with: pip install hypothesis"
            )


# Hypothesis example: demonstrating shrinking behavior
def example_hypothesis_shrinking():
    """
    Example showing how Hypothesis shrinks failing cases.

    If a complex predicate fails:
      "(field_0 > 50 AND field_1 = 'blue') OR (field_2 < 10 AND field_3 != 'red')"

    Hypothesis will shrink to minimal failing case:
      "field_0 > 50"  (or whatever the actual minimal case is)

    This makes debugging much easier than random testing.
    """
    from hypothesis import given, strategies as st

    @given(st.integers(), st.integers())
    def test_addition_commutative(a, b):
        assert a + b == b + a  # Always passes

    @given(st.integers(), st.integers())
    def test_subtraction_commutative(a, b):
        assert a - b == b - a  # Will fail and shrink to (1, 0)

    test_addition_commutative()
    # test_subtraction_commutative()  # Uncomment to see shrinking


if __name__ == "__main__":
    print("Hypothesis TLP Property Example")
    print("=" * 60)

    if HYPOTHESIS_AVAILABLE:
        print("✓ Hypothesis is available")
        print("\nTo use:")
        print("  1. Initialize OpenSearch with test data")
        print("  2. Create IndexContext")
        print("  3. Run: HypothesisTLP().check(context, client)")
        print("\nBenefits:")
        print("  - Automatic shrinking to minimal failing case")
        print("  - Better test distribution than random")
        print("  - Deterministic replay with @example decorator")
    else:
        print("✗ Hypothesis not installed")
        print("\nInstall with: pip install hypothesis")
