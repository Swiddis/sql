"""
Property-based tests for RENAME command interactions.

These properties would catch Issue #5150 (dedup nullifies renamed fields).
"""

from typing import List, Any
import random
from opensearchpy import OpenSearch
from opensearchpy.helpers import bulk

from ppl_correctness.datagen.context import IndexContext, Field, FieldType
from ppl_correctness.properties.base import Property, PropertyViolation


def create_rename_test_context(client: OpenSearch, seed: int = 42) -> IndexContext:
    """Create test context for rename testing"""
    rng = random.Random(seed)
    index_name = "test_rename"

    if client.indices.exists(index=index_name):
        client.indices.delete(index=index_name)

    mapping = {
        "mappings": {
            "properties": {
                "category": {"type": "keyword"},
                "subcategory": {"type": "keyword"},
                "value": {"type": "double"},
                "count": {"type": "integer"},
            }
        }
    }
    client.indices.create(index=index_name, body=mapping)

    # Insert test data with duplicate categories
    categories = ['A', 'B', 'C']
    subcategories = ['X', 'Y', 'Z']

    documents = []
    for i in range(30):
        doc = {
            "_index": index_name,
            "_id": i,
            "_source": {
                "category": rng.choice(categories),
                "subcategory": rng.choice(subcategories),
                "value": rng.uniform(10.0, 100.0),
                "count": rng.randint(1, 20),
            }
        }
        documents.append(doc)

    bulk(client, documents)
    client.indices.refresh(index=index_name)

    return IndexContext(
        name=index_name,
        fields=[
            Field("category", FieldType.KEYWORD),
            Field("subcategory", FieldType.KEYWORD),
            Field("value", FieldType.DOUBLE),
            Field("count", FieldType.INTEGER),
        ],
        doc_count=30
    )


class RenameFieldPreservation(Property):
    """
    Test: RENAME should preserve field values across all operations.

    Property: | rename field1 as alias | command | fields alias
    should return same values as: | command | fields field1

    This catches Issue #5150: renamed fields become null after dedup.
    """

    @property
    def name(self) -> str:
        return "RenameFieldPreservation"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        rename_context = create_rename_test_context(client)

        try:
            # Test 1: Rename without dedup (control)
            query_control = f"source={rename_context.name} | rename value as val | fields category, val | head 5"
            result_control = self._execute_ppl(client, query_control)

            # All val values should be non-null
            null_vals_control = [row for row in result_control if row[1] is None]
            if null_vals_control:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query_control,
                    expected="All non-null values",
                    actual=f"{len(null_vals_control)} null values",
                    message="RENAME alone causes null values (baseline broken)"
                ))

            # Test 2: Rename with dedup on DIFFERENT field (bug case)
            query_bug = f"source={rename_context.name} | rename value as val | dedup category | fields category, val"
            result_bug = self._execute_ppl(client, query_bug)

            # Property: val should have non-null values
            null_vals_bug = [row for row in result_bug if row[1] is None]

            if null_vals_bug:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query_bug,
                    expected="Renamed field values preserved after dedup",
                    actual=f"All {len(null_vals_bug)} values are null",
                    message="RENAME + DEDUP on different field nullifies renamed field. "
                            "This is Issue #5150."
                ))

            # Test 3: Dedup without rename (control)
            query_no_rename = f"source={rename_context.name} | dedup category | fields category, value"
            result_no_rename = self._execute_ppl(client, query_no_rename)

            null_vals_no_rename = [row for row in result_no_rename if row[1] is None]
            if null_vals_no_rename:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query_no_rename,
                    expected="All non-null values",
                    actual=f"{len(null_vals_no_rename)} null values",
                    message="DEDUP alone causes null values (baseline broken)"
                ))

            # Test 4: Rename the dedup field itself (should work per issue)
            query_rename_dedup_field = (f"source={rename_context.name} | "
                                       f"rename category as cat | "
                                       f"dedup cat | "
                                       f"fields cat, value")
            result_rename_dedup_field = self._execute_ppl(client, query_rename_dedup_field)

            # Both cat and value should be non-null
            null_cats = [row for row in result_rename_dedup_field if row[0] is None]
            null_vals = [row for row in result_rename_dedup_field if row[1] is None]

            if null_cats:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query_rename_dedup_field,
                    expected="Non-null category values",
                    actual=f"{len(null_cats)} null categories",
                    message="Renaming the dedup field itself causes nulls"
                ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query="Rename preservation test",
                expected="Successful execution",
                actual=str(e),
                message=f"Query failed: {e}"
            ))
        finally:
            if client.indices.exists(index=rename_context.name):
                client.indices.delete(index=rename_context.name)

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])


class RenameEvalInteraction(Property):
    """
    Test: RENAME and EVAL should compose correctly.

    Property: eval column = field | rename column as alias
    should preserve values.

    From Issue #5150, eval column references also become null after dedup.
    """

    @property
    def name(self) -> str:
        return "RenameEvalInteraction"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        rename_context = create_rename_test_context(client)

        try:
            # Test: eval with column reference + rename + dedup
            query = (f"source={rename_context.name} | "
                    f"eval val2 = value | "
                    f"rename value as val | "
                    f"dedup category | "
                    f"fields category, val, val2")

            result = self._execute_ppl(client, query)

            # Check for nulls in renamed field
            null_vals = [row for row in result if row[1] is None]
            # Check for nulls in eval'd field
            null_val2s = [row for row in result if row[2] is None]

            if null_vals:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected="Non-null renamed values",
                    actual=f"{len(null_vals)} null values in val",
                    message="RENAME + DEDUP nullifies renamed field"
                ))

            if null_val2s:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected="Non-null eval'd column reference",
                    actual=f"{len(null_val2s)} null values in val2",
                    message="EVAL column reference + DEDUP nullifies eval'd field. "
                            "Related to Issue #5150."
                ))

            # Test eval with expression (should work according to issue)
            query_expr = (f"source={rename_context.name} | "
                         f"eval doubled = value * 2 | "
                         f"dedup category | "
                         f"fields category, doubled")

            result_expr = self._execute_ppl(client, query_expr)
            null_doubled = [row for row in result_expr if row[1] is None]

            # This should NOT fail (eval expressions survive)
            # But if it does, it indicates a broader issue
            if null_doubled:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query_expr,
                    expected="Non-null eval'd expression",
                    actual=f"{len(null_doubled)} null values",
                    message="EVAL expression + DEDUP causes nulls (unexpected)"
                ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query="Rename/eval interaction test",
                expected="Successful execution",
                actual=str(e),
                message=f"Query failed: {e}"
            ))
        finally:
            if client.indices.exists(index=rename_context.name):
                client.indices.delete(index=rename_context.name)

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])


class CommandOrderIndependence(Property):
    """
    Test: Command order shouldn't affect field availability (within reason).

    Property: For commutative-like operations, order shouldn't cause nulls.

    Example: rename then dedup should work like dedup then rename (if fields match).
    """

    @property
    def name(self) -> str:
        return "CommandOrderIndependence"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        rename_context = create_rename_test_context(client)

        try:
            # Order 1: rename then sort
            query1 = f"source={rename_context.name} | rename value as val | sort val | fields category, val | head 5"
            result1 = self._execute_ppl(client, query1)

            # Order 2: sort then rename
            query2 = f"source={rename_context.name} | sort value | rename value as val | fields category, val | head 5"
            result2 = self._execute_ppl(client, query2)

            # Both should have non-null values
            null_count1 = len([row for row in result1 if row[1] is None])
            null_count2 = len([row for row in result2 if row[1] is None])

            if null_count1 > 0 and null_count2 == 0:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=f"Order1: {query1}",
                    expected="Non-null values like Order2",
                    actual=f"{null_count1} nulls",
                    message="RENAME then SORT causes nulls, but SORT then RENAME works"
                ))

            if null_count2 > 0 and null_count1 == 0:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=f"Order2: {query2}",
                    expected="Non-null values like Order1",
                    actual=f"{null_count2} nulls",
                    message="SORT then RENAME causes nulls, but RENAME then SORT works"
                ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query="Command order test",
                expected="Successful execution",
                actual=str(e),
                message=f"Query failed: {e}"
            ))
        finally:
            if client.indices.exists(index=rename_context.name):
                client.indices.delete(index=rename_context.name)

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])
