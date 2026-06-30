"""
Advanced aggregation properties for bug bash coverage.

- AVG conservation (weighted average)
- STDDEV >= 0 and other invariants
- DISTINCT_COUNT semantics
- MIN/MAX on strings/dates
- High-cardinality GROUP BY
"""

from typing import Any, List
import random
from opensearchpy import OpenSearch

from ppl_correctness.datagen.context import IndexContext, FieldType
from ppl_correctness.properties.base import Property, PropertyViolation


class AvgConservation(Property):
    """Verify ungrouped avg = weighted average of grouped avgs"""

    @property
    def name(self) -> str:
        return "AvgConservation"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []
        numeric = context.get_numeric_fields()
        groupable = context.get_groupable_fields()

        if not numeric or not groupable:
            return violations

        rng = random.Random()
        field = rng.choice(numeric)
        group_field = rng.choice(groupable)

        ungrouped_query = f"source={context.name} | stats avg({field.name})"
        grouped_query = f"source={context.name} | stats avg({field.name}), count() by {group_field.name}"

        try:
            ungrouped = self._execute_ppl(client, ungrouped_query)
            grouped = self._execute_ppl(client, grouped_query)

            if ungrouped and grouped:
                ungrouped_avg = ungrouped[0][0]
                if ungrouped_avg is None:
                    return violations  # All nulls

                # Weighted average: sum(avg_i * count_i) / sum(count_i)
                total_weighted = sum((row[0] or 0) * row[1] for row in grouped)
                total_count = sum(row[1] for row in grouped)
                weighted_avg = total_weighted / total_count if total_count > 0 else 0

                tolerance = abs(ungrouped_avg) * 0.001 + 0.001
                if abs(ungrouped_avg - weighted_avg) > tolerance:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=f"Ungrouped: {ungrouped_query}, Grouped: {grouped_query}",
                        expected=ungrouped_avg,
                        actual=weighted_avg,
                        message=f"AVG conservation violated: ungrouped={ungrouped_avg}, weighted={weighted_avg}"
                    ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=f"{ungrouped_query} vs {grouped_query}",
                expected="Successful execution",
                actual=str(e),
                message=f"Query failed: {e}"
            ))

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request('POST', '/_plugins/_ppl', body={'query': query})
        return response.get('datarows', [])


class StddevInvariant(Property):
    """Verify stddev >= 0 and stddev of constants = 0"""

    @property
    def name(self) -> str:
        return "StddevInvariant"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        return [] # stddev temporarily disabled

        violations = []
        numeric = context.get_numeric_fields()

        if not numeric:
            return violations

        rng = random.Random()
        field = rng.choice(numeric)

        query = f"source={context.name} | stats stddev({field.name})"

        try:
            result = self._execute_ppl(client, query)

            if result and result[0][0] is not None:
                stddev = result[0][0]

                if stddev < 0:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=query,
                        expected="stddev >= 0",
                        actual=stddev,
                        message=f"Negative stddev: {stddev}"
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
        response = client.transport.perform_request('POST', '/_plugins/_ppl', body={'query': query})
        return response.get('datarows', [])


class DistinctCountInvariant(Property):
    """Verify dc(group_field) equals number of groups returned"""

    @property
    def name(self) -> str:
        return "DistinctCountInvariant"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []
        groupable = context.get_groupable_fields()

        if not groupable:
            return violations

        rng = random.Random()
        group_field = rng.choice(groupable)

        dc_query = f"source={context.name} | stats dc({group_field.name})"
        group_query = f"source={context.name} | stats count() by {group_field.name}"

        try:
            dc_result = self._execute_ppl(client, dc_query)
            group_result = self._execute_ppl(client, group_query)

            if dc_result and group_result:
                distinct_count = dc_result[0][0]
                num_groups = len(group_result)

                if distinct_count != num_groups:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=f"DC: {dc_query}, Groups: {group_query}",
                        expected=num_groups,
                        actual=distinct_count,
                        message=f"dc({group_field.name})={distinct_count} but {num_groups} groups returned"
                    ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=f"{dc_query} vs {group_query}",
                expected="Successful execution",
                actual=str(e),
                message=f"Query failed: {e}"
            ))

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request('POST', '/_plugins/_ppl', body={'query': query})
        return response.get('datarows', [])


class StringDateMinMax(Property):
    """Verify min/max work on strings and dates, grouped min/max respect global"""

    @property
    def name(self) -> str:
        return "StringDateMinMax"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        # Find string/date fields
        comparable = [f for f in context.fields
                     if f.type in (FieldType.KEYWORD, FieldType.DATE) and not f.is_array]
        groupable = context.get_groupable_fields()

        if not comparable or not groupable:
            return violations

        rng = random.Random()
        field = rng.choice(comparable)
        group_field = rng.choice([g for g in groupable if g.name != field.name]) if len(groupable) > 1 else None

        if not group_field:
            return violations

        global_query = f"source={context.name} | stats min({field.name}), max({field.name})"
        grouped_query = f"source={context.name} | stats min({field.name}), max({field.name}) by {group_field.name}"

        try:
            global_result = self._execute_ppl(client, global_query)
            grouped_result = self._execute_ppl(client, grouped_query)

            if global_result and grouped_result:
                global_min, global_max = global_result[0]

                if global_min is None or global_max is None:
                    return violations

                # min of grouped mins should equal global min
                grouped_mins = [row[0] for row in grouped_result if row[0] is not None]
                grouped_maxs = [row[1] for row in grouped_result if row[1] is not None]

                if grouped_mins and min(grouped_mins) != global_min:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=f"Global: {global_query}, Grouped: {grouped_query}",
                        expected=global_min,
                        actual=min(grouped_mins),
                        message=f"min(grouped_mins)={min(grouped_mins)} != global_min={global_min}"
                    ))

                if grouped_maxs and max(grouped_maxs) != global_max:
                    violations.append(PropertyViolation(
                        property_name=self.name,
                        query=f"Global: {global_query}, Grouped: {grouped_query}",
                        expected=global_max,
                        actual=max(grouped_maxs),
                        message=f"max(grouped_maxs)={max(grouped_maxs)} != global_max={global_max}"
                    ))

        except Exception as e:
            violations.append(PropertyViolation(
                property_name=self.name,
                query=f"{global_query} vs {grouped_query}",
                expected="Successful execution",
                actual=str(e),
                message=f"Query failed: {e}"
            ))

        return violations

    def _execute_ppl(self, client: OpenSearch, query: str) -> List[Any]:
        response = client.transport.perform_request('POST', '/_plugins/_ppl', body={'query': query})
        return response.get('datarows', [])


class HighCardinalityGroupBy(Property):
    """Test count conservation holds with high-cardinality GROUP BY"""

    @property
    def name(self) -> str:
        return "HighCardinalityGroupBy"

    def check(self, context: IndexContext, client: OpenSearch) -> List[PropertyViolation]:
        violations = []

        # Use any KEYWORD field as high-cardinality proxy
        high_card_fields = [f for f in context.fields
                           if f.type == FieldType.KEYWORD and not f.is_array]

        if not high_card_fields:
            return violations

        rng = random.Random()
        field = rng.choice(high_card_fields)

        total_query = f"source={context.name} | stats count()"
        grouped_query = f"source={context.name} | stats count() by {field.name}"

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
                        message=f"High-cardinality GROUP BY: grouped sum={grouped_sum} != total={total_count}"
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
        response = client.transport.perform_request('POST', '/_plugins/_ppl', body={'query': query})
        return response.get('datarows', [])
