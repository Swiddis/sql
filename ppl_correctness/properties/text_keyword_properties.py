"""
Property-based tests for text fields with keyword subfields.

These properties would catch Issue #4463 (isnotnull filter not applied with
text fields having keyword subfields with ignore_above).
"""

from typing import List, Any
import random
from opensearchpy import OpenSearch
from opensearchpy.helpers import bulk

from ppl_correctness.datagen.context import IndexContext, Field, FieldType
from ppl_correctness.properties.base import Property, PropertyViolation


def create_text_keyword_context(client: OpenSearch, seed: int = 42) -> IndexContext:
    """Create test context with text fields having keyword subfields"""
    rng = random.Random(seed)
    index_name = "test_text_keyword"

    if client.indices.exists(index=index_name):
        client.indices.delete(index=index_name)

    # Key: text field with keyword subfield with ignore_above
    mapping = {
        "mappings": {
            "properties": {
                "id": {"type": "integer"},
                "description": {
                    "type": "text",
                    "fields": {
                        "keyword": {
                            "type": "keyword",
                            "ignore_above": 50  # Key condition for bug #4463
                        }
                    }
                },
                "value": {"type": "long"}
            }
        }
    }
    client.indices.create(index=index_name, body=mapping)

    # Insert mix of short and long descriptions
    short_descs = ["Short 1", "Short 2", "Short 3", ""]
    long_desc = "This is a very long description that definitely exceeds the 50 character limit"

    documents = []
    for i in range(30):
        if i < 10:
            desc = rng.choice(short_descs)
        elif i < 20:
            desc = long_desc + f" variation {i}"  # Long descriptions
        else:
            desc = ""  # Empty strings

        doc = {
            "_index": index_name,
            "_id": i,
            "_source": {
                "id": i,
                "description": desc,
                "value": rng.randint(0, 200)
            }
        }
        documents.append(doc)

    bulk(client, documents)
    client.indices.refresh(index=index_name)

    return IndexContext(
        name=index_name,
        fields=[
            Field("id", FieldType.INTEGER),
            Field("description", FieldType.TEXT),
            Field("value", FieldType.LONG),
        ],
        doc_count=30
    )


class IsNotNullFilterAggregation(Property):
    """
    Test: isnotnull() filter should exclude nulls from aggregation results.

    Property: If WHERE isnotnull(field) is applied, STATS BY field should not
    contain null buckets.

    This catches Issue #4463: isnotnull(description) filter doesn't prevent
    null buckets when description.keyword is null due to ignore_above.
    """

    @property
    def name(self) -> str:
        return "IsNotNullFilterAggregation"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        text_context = create_text_keyword_context(client)

        try:
            # Query with isnotnull filter and aggregation
            query = (f"source={text_context.name} | "
                    f"where isnotnull(description) and description != '' | "
                    f"stats count() by description")

            result = self._execute_ppl(client, query)

            # Property: No null values should appear in grouped results
            null_buckets = [row for row in result if row[1] is None]

            if null_buckets:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected="No null values in aggregation results",
                    actual=f"Found {len(null_buckets)} null buckets: {null_buckets[0]}",
                    message="isnotnull(description) filter failed: null values in GROUP BY results. "
                            "This is Issue #4463 - filter on text field, aggregation on keyword subfield."
                ))

            # Additional check: Verify filter is actually applied
            query_no_filter = f"source={text_context.name} | stats count() by description"
            result_no_filter = self._execute_ppl(client, query_no_filter)

            count_filtered = len(result)
            count_unfiltered = len(result_no_filter)

            # If counts are same, filter might not be applied
            if count_filtered >= count_unfiltered:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected=f"Fewer groups than unfiltered ({count_unfiltered})",
                    actual=f"Same or more groups: {count_filtered}",
                    message="isnotnull() filter appears to have no effect on aggregation"
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
            if client.indices.exists(index=text_context.name):
                client.indices.delete(index=text_context.name)

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])


class FilterAggregationFieldConsistency(Property):
    """
    Test: Field used in filter should match field used in aggregation.

    Property: For text fields with keyword subfields:
    - Filter operates on text field
    - Aggregation operates on keyword subfield
    - This mismatch can cause inconsistent results

    This is a generalized test for field mapping issues.
    """

    @property
    def name(self) -> str:
        return "FilterAggregationFieldConsistency"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        text_context = create_text_keyword_context(client)

        try:
            # Test 1: Count documents where description exists
            query_exists = f"source={text_context.name} | where isnotnull(description) | stats count()"
            count_exists = self._execute_ppl(client, query_exists)[0][0]

            # Test 2: Count non-null groups in aggregation
            query_agg = f"source={text_context.name} | where isnotnull(description) | stats count() by description"
            result_agg = self._execute_ppl(client, query_agg)

            # Sum of group counts should equal filtered document count
            sum_groups = sum(row[0] for row in result_agg)

            # Property: Conservation of count
            if sum_groups != count_exists:
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=f"Filter: {query_exists}, Agg: {query_agg}",
                    expected=count_exists,
                    actual=sum_groups,
                    message=f"Count mismatch: filter found {count_exists} docs, "
                            f"but aggregation groups sum to {sum_groups}. "
                            f"Indicates field mapping inconsistency between filter and aggregation."
                ))

            # Test 3: Check for null groups when filter explicitly excludes nulls
            null_groups = [row for row in result_agg if row[1] is None]
            if null_groups:
                null_count = null_groups[0][0]
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query_agg,
                    expected="No null groups after isnotnull filter",
                    actual=f"Null group with count={null_count}",
                    message=f"Found {null_count} documents in null bucket despite isnotnull() filter. "
                            f"Text field exists but keyword subfield is null."
                ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query="Field consistency test",
                expected="Successful execution",
                actual=str(e),
                message=f"Query failed: {e}"
            ))
        finally:
            if client.indices.exists(index=text_context.name):
                client.indices.delete(index=text_context.name)

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request(
            'POST',
            '/_plugins/_ppl',
            body={'query': query}
        )
        return response.get('datarows', [])
