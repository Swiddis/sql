"""
Extraction Equivalence Property

Tests that extraction commands (spath, rex, lookup) produce results equivalent
to querying pre-extracted/joined data.

Property: source=raw | extract | tail ≡ source=extracted | tail
"""

from typing import List
from opensearchpy import OpenSearch
import random

from ppl_correctness.datagen.context import CorrelatedIndexSet, Relationship, FieldType
from ppl_correctness.properties.base import Property, PropertyViolation
from ppl_correctness.generators.ppl import PPLQueryGenerator


class ExtractionEquivalenceProperty(Property):
    """Extraction commands equivalent to querying pre-extracted index"""

    @property
    def name(self) -> str:
        return "extraction_equivalence"

    def check(self, corr_set: CorrelatedIndexSet, client: OpenSearch) -> List[PropertyViolation]:
        if not corr_set.relationships:
            return []

        rel = corr_set.relationships[0]
        violations = []

        if rel.type == 'spath':
            violations.extend(self._check_spath(corr_set, rel, client))
        elif rel.type == 'rex':
            violations.extend(self._check_rex(corr_set, rel, client))
        elif rel.type == 'lookup':
            violations.extend(self._check_lookup(corr_set, rel, client))

        return violations

    def _check_spath(self, corr_set: CorrelatedIndexSet, rel: Relationship, client: OpenSearch) -> List[PropertyViolation]:
        """Test spath extraction equivalence"""
        violations = []
        extracted_ctx = corr_set.variants['extracted']
        rng = random.Random()

        # Test 1: Simple count (no tail)
        src_field = list(rel.mapping.keys())[0]
        tgt_fields = rel.mapping[src_field]

        # Build spath extraction commands
        spath_cmds = [f"spath path={field} input={src_field}" for field in tgt_fields]

        # Cast extracted strings to correct types
        cast_cmds = []
        for field in extracted_ctx.fields:
            if field.name == 'id':
                continue  # Skip id field
            if field.type == FieldType.INTEGER:
                cast_cmds.append(f"eval {field.name}=cast({field.name} as integer)")
            elif field.type == FieldType.DOUBLE:
                cast_cmds.append(f"eval {field.name}=cast({field.name} as double)")

        q1 = f"source={corr_set.base.name} | {' | '.join(spath_cmds)} | {' | '.join(cast_cmds)} | stats count()"
        q2 = f"source={extracted_ctx.name} | stats count()"

        r1 = self._execute_query(client, q1)
        r2 = self._execute_query(client, q2)

        if not self._results_equal(r1, r2):
            violations.append(PropertyViolation(
                property_name='spath_count',
                query=q1,
                expected=r2,
                actual=r1,
                message=f'spath extraction count mismatch. Q1: {q1}, Q2: {q2}'
            ))

        # Test 2: WHERE on extracted field
        # Filter on status='ok'
        q1 = f"source={corr_set.base.name} | {' | '.join(spath_cmds)} | where status='ok' | stats count()"
        q2 = f"source={extracted_ctx.name} | where status='ok' | stats count()"

        r1 = self._execute_query(client, q1)
        r2 = self._execute_query(client, q2)

        if not self._results_equal(r1, r2):
            violations.append(PropertyViolation(
                property_name='spath_where',
                query=q1,
                expected=r2,
                actual=r1,
                message=f'spath + where mismatch. Q1: {q1}, Q2: {q2}'
            ))

        # Test 3: STATS by extracted field
        q1 = f"source={corr_set.base.name} | {' | '.join(spath_cmds)} | stats count() by status"
        q2 = f"source={extracted_ctx.name} | stats count() by status"

        r1 = self._execute_query(client, q1)
        r2 = self._execute_query(client, q2)

        if not self._results_equal(r1, r2):
            violations.append(PropertyViolation(
                property_name='spath_stats_by',
                query=q1,
                expected=r2,
                actual=r1,
                message=f'spath + stats by mismatch. Q1: {q1}, Q2: {q2}'
            ))

        # Test 4: STATS aggregation on numeric field
        q1 = f"source={corr_set.base.name} | {' | '.join(spath_cmds)} | {' | '.join(cast_cmds)} | stats avg(value)"
        q2 = f"source={extracted_ctx.name} | stats avg(value)"

        r1 = self._execute_query(client, q1)
        r2 = self._execute_query(client, q2)

        if not self._results_equal(r1, r2):
            violations.append(PropertyViolation(
                property_name='spath_stats_agg',
                query=q1,
                expected=r2,
                actual=r1,
                message=f'spath + stats avg mismatch. Q1: {q1}, Q2: {q2}'
            ))

        return violations

    def _check_rex(self, corr_set: CorrelatedIndexSet, rel: Relationship, client: OpenSearch) -> List[PropertyViolation]:
        """Test rex extraction equivalence"""
        violations = []
        extracted_ctx = corr_set.variants['extracted']
        rng = random.Random()

        src_field = list(rel.mapping.keys())[0]
        tgt_fields = rel.mapping[src_field]

        # Build rex extraction commands (key=value pattern)
        rex_cmds = []
        for field in tgt_fields:
            rex_cmds.append(f'rex field={src_field} "{field}=(?<{field}>[^,\\s]+)"')

        # Cast extracted strings to correct types
        cast_cmds = []
        for field in extracted_ctx.fields:
            if field.name == 'id':
                continue
            if field.type == FieldType.INTEGER:
                cast_cmds.append(f"eval {field.name}=cast({field.name} as integer)")

        # Test 1: Simple count
        q1 = f"source={corr_set.base.name} | {' | '.join(rex_cmds)} | {' | '.join(cast_cmds)} | stats count()"
        q2 = f"source={extracted_ctx.name} | stats count()"

        r1 = self._execute_query(client, q1)
        r2 = self._execute_query(client, q2)

        if not self._results_equal(r1, r2):
            violations.append(PropertyViolation(
                property_name='rex_count',
                query=q1,
                expected=r2,
                actual=r1,
                message=f'rex extraction count mismatch. Q1: {q1}, Q2: {q2}'
            ))

        # Test 2: WHERE on extracted field
        q1 = f"source={corr_set.base.name} | {' | '.join(rex_cmds)} | where user='alice' | stats count()"
        q2 = f"source={extracted_ctx.name} | where user='alice' | stats count()"

        r1 = self._execute_query(client, q1)
        r2 = self._execute_query(client, q2)

        if not self._results_equal(r1, r2):
            violations.append(PropertyViolation(
                property_name='rex_where',
                query=q1,
                expected=r2,
                actual=r1,
                message=f'rex + where mismatch. Q1: {q1}, Q2: {q2}'
            ))

        # Test 3: STATS by extracted field
        q1 = f"source={corr_set.base.name} | {' | '.join(rex_cmds)} | stats count() by status"
        q2 = f"source={extracted_ctx.name} | stats count() by status"

        r1 = self._execute_query(client, q1)
        r2 = self._execute_query(client, q2)

        if not self._results_equal(r1, r2):
            violations.append(PropertyViolation(
                property_name='rex_stats_by',
                query=q1,
                expected=r2,
                actual=r1,
                message=f'rex + stats by mismatch. Q1: {q1}, Q2: {q2}'
            ))

        return violations

    def _check_lookup(self, corr_set: CorrelatedIndexSet, rel: Relationship, client: OpenSearch) -> List[PropertyViolation]:
        """Test lookup/join equivalence"""
        violations = []
        joined_ctx = corr_set.variants['joined']

        # extraction_pattern format: "enrichment_table join_key"
        lookup_table, join_key = rel.extraction_pattern.split()

        # Test 1: Simple count (should match)
        q1 = f"source={corr_set.base.name} | lookup {lookup_table} {join_key} | stats count()"
        q2 = f"source={joined_ctx.name} | stats count()"

        r1 = self._execute_query(client, q1)
        r2 = self._execute_query(client, q2)

        if not self._results_equal(r1, r2):
            violations.append(PropertyViolation(
                property_name='lookup_count',
                query=q1,
                expected=r2,
                actual=r1,
                message=f'lookup count mismatch. Q1: {q1}, Q2: {q2}'
            ))

        # Test 2: Filter on joined field
        q1 = f"source={corr_set.base.name} | lookup {lookup_table} {join_key} | where department='eng' | stats count()"
        q2 = f"source={joined_ctx.name} | where department='eng' | stats count()"

        r1 = self._execute_query(client, q1)
        r2 = self._execute_query(client, q2)

        if not self._results_equal(r1, r2):
            violations.append(PropertyViolation(
                property_name='lookup_where',
                query=q1,
                expected=r2,
                actual=r1,
                message=f'lookup + where mismatch. Q1: {q1}, Q2: {q2}'
            ))

        # Test 3: Stats by joined field
        q1 = f"source={corr_set.base.name} | lookup {lookup_table} {join_key} | stats count() by department"
        q2 = f"source={joined_ctx.name} | stats count() by department"

        r1 = self._execute_query(client, q1)
        r2 = self._execute_query(client, q2)

        if not self._results_equal(r1, r2):
            violations.append(PropertyViolation(
                property_name='lookup_stats_by',
                query=q1,
                expected=r2,
                actual=r1,
                message=f'lookup + stats by mismatch. Q1: {q1}, Q2: {q2}'
            ))

        return violations

    def _execute_query(self, client: OpenSearch, query: str):
        """Execute PPL query and return results"""
        try:
            response = client.transport.perform_request(
                'POST',
                '/_plugins/_ppl',
                body={'query': query}
            )
            return response.get('datarows', [])
        except Exception as e:
            return {'error': str(e)}

    def _results_equal(self, r1, r2) -> bool:
        """Compare query results"""
        if isinstance(r1, dict) and 'error' in r1:
            return False
        if isinstance(r2, dict) and 'error' in r2:
            return False

        # Sort for order-independent comparison
        if isinstance(r1, list) and isinstance(r2, list):
            return sorted(str(r) for r in r1) == sorted(str(r) for r in r2)

        return r1 == r2
