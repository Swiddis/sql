"""
Property: Query pushdown verification

Ensures WHERE predicates and aggregations are pushed down to OpenSearch DSL
rather than post-processing in Calcite.

Critical case: Time range filters must use DSL range query (dashboards inject these).
"""

from typing import Any, List
import re
import json
from ppl_correctness.datagen.context import IndexContext, FieldType
from ppl_correctness.properties.base import Property, PropertyViolation


class PushdownProperty(Property):
    @property
    def name(self) -> str:
        return "pushdown"

    def check(self, context: IndexContext, client: Any) -> List[PropertyViolation]:
        violations = []

        # Check range filter pushdown (most critical for perf)
        numeric_fields = [f for f in context.fields
                          if f.type in [FieldType.INTEGER, FieldType.DOUBLE]
                          and not f.is_array]
        if numeric_fields:
            field = numeric_fields[0]
            query = f"source={context.name} | where {field.name} > 0 | stats count()"
            plan = self._get_physical_plan(client, query)
            if plan and not self._has_range_pushdown(plan, field.name):
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected="range query in DSL",
                    actual="no range query found",
                    message=f"Range filter on {field.name} not pushed down"
                ))

        # Check aggregation pushdown
        if numeric_fields:
            field = numeric_fields[0]
            query = f"source={context.name} | stats sum({field.name})"
            plan = self._get_physical_plan(client, query)
            if plan and not self._has_agg_pushdown(plan):
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected="aggregation in DSL",
                    actual="no aggregation found",
                    message=f"Aggregation on {field.name} not pushed down"
                ))

        return violations

    def _get_physical_plan(self, client: Any, query: str) -> str:
        """Get physical plan from EXPLAIN"""
        try:
            response = client.transport.perform_request(
                'POST',
                '/_plugins/_ppl',
                body={'query': f'EXPLAIN {query}'}
            )
            return response.get('calcite', {}).get('physical', '')
        except Exception:
            return ''

    def _has_range_pushdown(self, plan: str, field_name: str) -> bool:
        """Check if plan contains DSL range query for field"""
        # Look for: "query":{"range":{"field_name":...}}
        return f'"range":{{"{field_name}"' in plan

    def _has_agg_pushdown(self, plan: str) -> bool:
        """Check if plan contains DSL aggregation"""
        # Look for: "aggregations":{...}
        return '"aggregations":{' in plan


class TimeRangePushdownProperty(Property):
    """
    Critical special case: Dashboard time range filters

    Dashboards inject time filters like:
      | where `@timestamp` > '2026-06-30 00:00' and `@timestamp` < '2026-06-30 01:00'

    These MUST become DSL range queries since indices are time-partitioned.
    Missing this pushdown destroys performance.
    """

    @property
    def name(self) -> str:
        return "time-range-pushdown"

    def check(self, context: IndexContext, client: Any) -> List[PropertyViolation]:
        violations = []

        # Find timestamp/date field
        date_fields = [f for f in context.fields
                       if f.type == FieldType.DATE and not f.is_array]

        for field in date_fields:
            # Generate time range query
            query = (f"source={context.name} | "
                    f"where {field.name} > '2026-06-30 00:00' and "
                    f"{field.name} < '2026-06-30 01:00'")

            plan = self._get_physical_plan(client, query)
            if not plan:
                continue

            # Check for DSL range query
            if not self._has_range_pushdown(plan, field.name):
                violations.append(PropertyViolation(
                    property_name=self.name,
                    query=query,
                    expected="DSL range query",
                    actual="no range pushdown",
                    message=f"Time range filter on {field.name} not pushed down (dashboard perf critical)"
                ))

        return violations

    def _get_physical_plan(self, client: Any, query: str) -> str:
        try:
            response = client.transport.perform_request(
                'POST',
                '/_plugins/_ppl',
                body={'query': f'EXPLAIN {query}'}
            )
            return response.get('calcite', {}).get('physical', '')
        except Exception:
            return ''

    def _has_range_pushdown(self, plan: str, field_name: str) -> bool:
        return f'"range":{{"{field_name}"' in plan
