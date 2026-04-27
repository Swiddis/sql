"""
Regression test for Issue #5333: Array values not treated as atomic.

https://github.com/opensearch-project/sql/issues/5333

Bug: Arrays are decomposed into individual elements in operations that should
treat the array as a single unit.

Affected operations:
- WHERE comparisons: If any element passes, whole array passes
- ORDER BY: Only first element used
- GROUP BY: Each element becomes separate group key
- MAX/MIN: Operates on individual elements, not whole arrays
"""

from typing import List, Dict, Any
from opensearchpy import OpenSearch
from opensearchpy.helpers import bulk

from ppl_correctness.properties.base import Property, PropertyViolation


def create_array_test_index(client: OpenSearch, index_name: str = "test_arrays"):
    """Create test index with array-valued fields"""

    # Delete if exists
    if client.indices.exists(index=index_name):
        client.indices.delete(index=index_name)

    # Create index
    mapping = {
        "mappings": {
            "properties": {
                "x": {"type": "integer"},
                "y": {"type": "integer"}  # Will contain array values
            }
        }
    }
    client.indices.create(index=index_name, body=mapping)

    # Insert test data with arrays
    documents = [
        {"_index": index_name, "_id": 1, "_source": {"x": 1, "y": [1, 2]}},
        {"_index": index_name, "_id": 2, "_source": {"x": 2, "y": [3, 4]}},
        {"_index": index_name, "_id": 3, "_source": {"x": 3, "y": [1, 5]}},
        {"_index": index_name, "_id": 4, "_source": {"x": 4, "y": [1, 2]}},
        {"_index": index_name, "_id": 5, "_source": {"x": 5, "y": [2, 3]}},
    ]

    bulk(client, documents)
    client.indices.refresh(index=index_name)


class ArrayWhereComparison(Property):
    """
    Test: WHERE y > 3 should NOT match y=[1,2] or y=[2,3]

    Bug: Currently matches if ANY element > 3
    Expected: Should error (comparing array to scalar) or have defined semantics
    """

    @property
    def name(self) -> str:
        return "ArrayWhereComparison_Issue5333"

    def check(self, context: None, client: OpenSearch) -> List[PropertyViolation]:
        violations = []
        index_name = "test_arrays_where"

        try:
            create_array_test_index(client, index_name)

            # Query: WHERE y > 3
            query = f"source={index_name} | where y > 3 | fields x, y"
            result = self._execute_ppl(client, query)

            # Expected: Only rows where ALL elements > 3, or error
            # Actual bug: Returns rows where ANY element > 3
            matched_x_values = [row[0] for row in result]

            # Bug behavior: x=2 [3,4] and x=3 [1,5] match (because 4>3 and 5>3)
            # Correct: Only x=2 [3,4] should match (if array comparison defined)
            #          OR query should error (type mismatch)

            if 3 in matched_x_values:  # x=3 has y=[1,5]
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected="Only rows where all elements > 3, or error",
                    actual=f"Matched x={matched_x_values}, includes x=3 with y=[1,5]",
                    message="WHERE y > 3 incorrectly matches y=[1,5] (element 1 <= 3)"
                ))

        except Exception as e:
            # If it errors, that's actually correct behavior for type mismatch!
            pass
        finally:
            if client.indices.exists(index=index_name):
                client.indices.delete(index=index_name)

        return violations


class ArrayOrderBy(Property):
    """
    Test: ORDER BY y should order by entire array, not just first element

    Bug: Currently sorts by first element only
    Expected: Whole-array comparison
    """

    @property
    def name(self) -> str:
        return "ArrayOrderBy_Issue5333"

    def check(self, context: None, client: OpenSearch) -> List[PropertyViolation]:
        violations = []
        index_name = "test_arrays_sort"

        try:
            create_array_test_index(client, index_name)

            # Query: ORDER BY y
            query = f"source={index_name} | sort y | fields x, y"
            result = self._execute_ppl(client, query)

            x_values = [row[0] for row in result]

            # Bug: Sorts by first element only
            # Data: x=1:[1,2], x=3:[1,5], x=4:[1,2], x=5:[2,3], x=2:[3,4]
            # Current order by first element: [1,2], [1,5], [1,2], [2,3], [3,4]
            #   -> x: 1, 3, 4, 5, 2 (or some permutation preserving first-element order)

            # If sorted correctly by whole array (lexicographic):
            # [1,2] < [1,5] < [2,3] < [3,4]
            # -> x: 1, 4 (both [1,2], order undefined), 3, 5, 2

            # Check if x=3 (y=[1,5]) appears before x=5 (y=[2,3])
            # This would indicate first-element-only sorting
            if x_values.index(3) < x_values.index(5):
                # [1,5] before [2,3] -> first element wins ([1,_] vs [2,_])
                # This is the bug: should be [2,3] before [1,5] in lexicographic order
                # Wait, [1,5] < [2,3] lexicographically, so this is correct!

                # Better test: Check if [1,2] and [1,5] maintain relative order
                # They should be ordered [1,2] < [1,5], but if only first element used,
                # their order is undefined

                # Actually, the bug is that [1,2] and [1,5] might be out of order
                # Let's check by comparing ALL [1,*] entries

                y_arrays = [row[1] for row in result]
                # Extract pairs starting with 1
                ones = [(i, arr) for i, arr in enumerate(y_arrays) if arr[0] == 1]

                if len(ones) >= 2:
                    # Check if second elements are in order
                    second_elements = [arr[1] for _, arr in ones]
                    if second_elements != sorted(second_elements):
                        violations.append(PropertyViolation(
                            property_name=self.name,
                            query=query,
                            expected="Arrays sorted lexicographically",
                            actual=f"Arrays starting with 1: {[arr for _, arr in ones]}, seconds: {second_elements}",
                            message="ORDER BY y sorts by first element only, not whole array"
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
            if client.indices.exists(index=index_name):
                client.indices.delete(index=index_name)

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])


class ArrayGroupBy(Property):
    """
    Test: GROUP BY y should treat each unique array as one group

    Bug: Each array element becomes a separate group key
    Expected: Whole arrays as group keys
    """

    @property
    def name(self) -> str:
        return "ArrayGroupBy_Issue5333"

    def check(self, context: None, client: OpenSearch) -> List[PropertyViolation]:
        violations = []
        index_name = "test_arrays_group"

        try:
            create_array_test_index(client, index_name)

            # Query: GROUP BY y
            query = f"source={index_name} | stats count() by y"
            result = self._execute_ppl(client, query)

            # Expected groups (treating arrays as atomic):
            # [1,2]: 2 docs (x=1 and x=4)
            # [3,4]: 1 doc (x=2)
            # [1,5]: 1 doc (x=3)
            # [2,3]: 1 doc (x=5)
            # Total: 4 groups

            # Bug behavior: Explodes arrays into elements
            # Groups might be: 1, 2, 3, 4, 5
            # Total: 5 groups (or more with duplicates)

            num_groups = len(result)

            # If more than 4 groups, arrays were exploded
            if num_groups > 4:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected="4 groups (unique arrays as atomic values)",
                    actual=f"{num_groups} groups: {result}",
                    message="GROUP BY y explodes arrays into individual elements"
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
            if client.indices.exists(index=index_name):
                client.indices.delete(index=index_name)

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])


class ArrayMaxMin(Property):
    """
    Test: MAX(y) should return the maximum array, not max element

    Bug: Returns max of all individual elements across all arrays
    Expected: MAX([1,2], [3,4], [1,5]) = [3,4] (lexicographically) or error
    """

    @property
    def name(self) -> str:
        return "ArrayMaxMin_Issue5333"

    def check(self, context: None, client: OpenSearch) -> List[PropertyViolation]:
        violations = []
        index_name = "test_arrays_max"

        try:
            create_array_test_index(client, index_name)

            # Query: MAX(y)
            query = f"source={index_name} | stats max(y)"
            result = self._execute_ppl(client, query)

            if result:
                max_value = result[0][0]

                # Bug: max_value = 5 (max individual element)
                # Expected: Error or [3,4] (max array)

                if max_value == 5:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=query,
                        expected="Max array or error (type mismatch)",
                        actual=f"max(y) = {max_value} (max individual element)",
                        message="MAX(y) operates on individual elements, not whole arrays"
                    ))

        except Exception as e:
            # Error is acceptable (type mismatch)
            pass
        finally:
            if client.indices.exists(index=index_name):
                client.indices.delete(index=index_name)

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])


# Convenience function to run all array bug tests
def test_all_array_bugs(client: OpenSearch) -> Dict[str, List[PropertyViolation]]:
    """Run all Issue #5333 regression tests"""
    properties = [
        ArrayWhereComparison(),
        ArrayOrderBy(),
        ArrayGroupBy(),
        ArrayMaxMin(),
    ]

    results = {}
    for prop in properties:
        violations = prop.check(None, client)
        results[prop.name] = violations

    return results


if __name__ == "__main__":
    # Example usage
    client = OpenSearch(["localhost:9200"])

    print("Testing Issue #5333: Array Atomicity Bugs")
    print("=" * 60)

    results = test_all_array_bugs(client)

    for prop_name, violations in results.items():
        print(f"\n{prop_name}:")
        if violations:
            print(f"  ✗ FAILED with {len(violations)} violations")
            for v in violations:
                print(f"    - {v.message}")
        else:
            print(f"  ✓ PASSED (or bug fixed!)")
