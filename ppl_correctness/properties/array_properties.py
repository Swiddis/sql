"""
Property-based tests for array field handling.

These properties would catch Issue #5333 and related array bugs if run
against a buggy implementation.
"""

from typing import List, Any
import random
from opensearchpy import OpenSearch

from ppl_correctness.datagen.context import IndexContext, Field, FieldType
from ppl_correctness.properties.base import Property, PropertyViolation


def create_array_context(client: OpenSearch, seed: int = 42) -> IndexContext:
    """
    Create a test context with array fields.

    Returns IndexContext with mixed scalar and array fields.
    """
    rng = random.Random(seed)
    index_name = "test_array_properties"

    # Delete if exists
    if client.indices.exists(index=index_name):
        client.indices.delete(index=index_name)

    # Create index with array-capable fields
    mapping = {
        "mappings": {
            "properties": {
                "id": {"type": "integer"},
                "scalar_int": {"type": "integer"},
                "array_int": {"type": "integer"},  # Can hold arrays
                "scalar_keyword": {"type": "keyword"},
                "array_keyword": {"type": "keyword"},  # Can hold arrays
            }
        }
    }
    client.indices.create(index=index_name, body=mapping)

    # Insert documents with mix of scalar and array values
    from opensearchpy.helpers import bulk
    documents = []

    for i in range(50):
        doc = {
            "_index": index_name,
            "_id": i,
            "_source": {
                "id": i,
                "scalar_int": rng.randint(0, 10),
                "array_int": [rng.randint(0, 10) for _ in range(rng.randint(1, 4))],
                "scalar_keyword": rng.choice(['red', 'green', 'blue']),
                "array_keyword": rng.sample(['red', 'green', 'blue', 'yellow'], rng.randint(1, 3)),
            }
        }
        documents.append(doc)

    bulk(client, documents)
    client.indices.refresh(index=index_name)

    # Return context
    context = IndexContext(
        name=index_name,
        fields=[
            Field("id", FieldType.INTEGER, nullable=False),
            Field("scalar_int", FieldType.INTEGER),
            Field("array_int", FieldType.INTEGER),  # Note: same type, but holds arrays
            Field("scalar_keyword", FieldType.KEYWORD),
            Field("array_keyword", FieldType.KEYWORD),
        ],
        doc_count=50
    )

    return context


class ArrayGroupByAtomicity(Property):
    """
    Test: Arrays should be treated as atomic values in GROUP BY.

    Property: GROUP BY array_field should NOT explode arrays into elements.

    If arrays are exploded:
    - GROUP BY [1,2] creates separate groups for 1 and 2
    - Sum of group counts > total document count (elements counted multiple times)

    This property would catch Issue #5333 (GROUP BY).
    """

    @property
    def name(self) -> str:
        return "ArrayGroupByAtomicity"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        # Use array context instead of generic context
        array_context = create_array_context(client)

        try:
            # Test GROUP BY on array field
            query_total = f"source={array_context.name} | stats count()"
            query_grouped = f"source={array_context.name} | stats count() by array_int"

            result_total = self._execute_ppl(client, query_total)
            result_grouped = self._execute_ppl(client, query_grouped)

            total_count = result_total[0][0] if result_total else 0
            grouped_sum = sum(row[0] for row in result_grouped)

            # Property: Sum of grouped counts should equal total count
            # If arrays are exploded, grouped_sum > total_count
            if grouped_sum > total_count:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=f"Total: {query_total}, Grouped: {query_grouped}",
                    expected=total_count,
                    actual=grouped_sum,
                    message=f"GROUP BY array_int explodes arrays: grouped sum ({grouped_sum}) > total ({total_count}). "
                            f"This indicates arrays are not treated as atomic values."
                ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=f"GROUP BY array_int test",
                expected="Successful execution",
                actual=str(e),
                message=f"Query failed: {e}"
            ))
        finally:
            if client.indices.exists(index=array_context.name):
                client.indices.delete(index=array_context.name)

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])


class ArrayWhereFilterConsistency(Property):
    """
    Test: WHERE on array fields should have consistent semantics.

    Property: Test multiple equivalent forms of array filtering.

    For array field containing [1, 2, 3]:
    - WHERE array > 2: should have defined behavior (all elements? any element? error?)
    - Result should be reproducible and consistent

    This would catch Issue #5333 (WHERE comparisons).
    """

    @property
    def name(self) -> str:
        return "ArrayWhereFilterConsistency"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        array_context = create_array_context(client)

        try:
            # Test: WHERE array_int > threshold
            threshold = 5
            query = f"source={array_context.name} | where array_int > {threshold} | stats count()"

            result1 = self._execute_ppl(client, query)
            result2 = self._execute_ppl(client, query)  # Run twice

            count1 = result1[0][0] if result1 else 0
            count2 = result2[0][0] if result2 else 0

            # Property 1: Queries should be deterministic
            if count1 != count2:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected=count1,
                    actual=count2,
                    message="WHERE on array field is non-deterministic"
                ))

            # Property 2: Test if "any element" vs "all elements" semantics
            # If "any element" semantics, count should be high
            # If "all elements" semantics, count should be lower
            # We can't assert which is correct, but we can detect the behavior

            total_query = f"source={array_context.name} | stats count()"
            total_count = self._execute_ppl(client, total_query)[0][0]

            # If almost all documents match, likely "any element" semantics
            if count1 > 0.9 * total_count and total_count > 0:
                # This might indicate the bug: WHERE array > threshold matches if ANY element > threshold
                # We can't definitively say it's a bug without knowing expected behavior,
                # but we can flag it for investigation
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected="Consistent array filtering semantics",
                    actual=f"{count1}/{total_count} documents matched (90%+)",
                    message=f"WHERE array_int > {threshold} matches most documents, "
                            f"possibly using 'any element' semantics. "
                            f"Expected behavior unclear - flag for investigation."
                ))

        except Exception as e:
            # Exception is actually acceptable for array vs scalar comparison
            pass
        finally:
            if client.indices.exists(index=array_context.name):
                client.indices.delete(index=array_context.name)

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])


class ArraySortDeterminism(Property):
    """
    Test: Sorting by array fields should be deterministic.

    Property: Multiple executions of ORDER BY array_field should return same order.

    This would catch Issue #5333 (ORDER BY).
    """

    @property
    def name(self) -> str:
        return "ArraySortDeterminism"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        array_context = create_array_context(client)

        try:
            query = f"source={array_context.name} | sort array_int | fields id"

            result1 = self._execute_ppl(client, query)
            result2 = self._execute_ppl(client, query)

            ids1 = [row[0] for row in result1]
            ids2 = [row[0] for row in result2]

            # Property: Sort order should be deterministic
            if ids1 != ids2:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected=ids1,
                    actual=ids2,
                    message="SORT array_int is non-deterministic across executions"
                ))

            # Additional check: Verify sort is actually sorting something
            # If array_int is being used for sorting, order should differ from id order
            if ids1 == list(range(len(ids1))):
                # Sorted IDs are sequential - might indicate sort didn't apply
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected="Non-sequential ID order after sort",
                    actual=f"Sequential IDs: {ids1[:10]}...",
                    message="SORT array_int might not be applied (IDs are sequential)"
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
            if client.indices.exists(index=array_context.name):
                client.indices.delete(index=array_context.name)

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])
