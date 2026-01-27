# POC: Lookup Pre-Filtering Optimization

## Overview
Optimize discriminated lookups by pre-executing the lookup scan and pushing join key filters into the main table scan.

## Problem Statement
From query plan `/tmp/calcite-lookup.txt`:
- **Lookup table (dim_lookup)**: Filtered by discriminator + additional conditions → Small result set (e.g., 10-100 rows)
- **Main table (request_logs)**: Full table scan with NO filters → Large result set (millions of rows)
- **Join**: In-memory hash join between small lookup and ENTIRE main table

## Optimization Opportunity
If the filtered lookup returns a small set of distinct join keys:
1. Pre-execute the lookup scan
2. Extract distinct join key values (e.g., `[key1, key2, ..., keyN]`)
3. Push terms filter into main table: `WHERE host_key IN (key1, key2, ..., keyN)`
4. Execute much smaller join

**Expected speedup**: 10-100x for selective lookups

## Implementation Status

### ✅ Completed
1. **Terms Filter Infrastructure** (`sql-5y7`)
   - Created `TermsFilterDigest` record class
   - Added `TERMS_FILTER` push-down type
   - Updated `AbstractCalciteIndexScan.estimateRowCount()` to handle terms filters

2. **Detection Logic** (`sql-6rn`)
   - Created `LookupPreFilterRule` Calcite rule
   - Pattern matching: LEFT JOIN with filtered right side (lookup)
   - Registered in `OpenSearchIndexRules.OPEN_SEARCH_PUSHDOWN_RULES`
   - Currently logs detection, transformation TBD

3. **Integration Test** (`sql-azv`)
   - Test: `CalcitePPLLookupIT.testDiscriminatorWithAdditionalFilters()`
   - Scenario: 10 request_logs → lookup dim_lookup.host with filters
   - Verifies correct result set (7 rows matching host_key 1 or 3)

### ⏳ Remaining Work

4. **Pre-execution & Transformation** (`sql-nrr`)
   - Implement `LookupPreFilterRule.onMatch()`:
     - Estimate cardinality of lookup result
     - If < threshold (100), pre-execute lookup scan
     - Extract distinct join key values
     - Create `TermsFilterDigest` with values
     - Inject into left scan's `PushDownContext`
     - Build terms query in `OpenSearchRequestBuilder`
     - Return transformed plan

## Key Files

### Infrastructure
- `opensearch/src/main/java/org/opensearch/sql/opensearch/storage/scan/context/TermsFilterDigest.java`
- `opensearch/src/main/java/org/opensearch/sql/opensearch/storage/scan/context/PushDownType.java` (added TERMS_FILTER)
- `opensearch/src/main/java/org/opensearch/sql/opensearch/storage/scan/AbstractCalciteIndexScan.java` (estimateRowCount)

### Rule
- `opensearch/src/main/java/org/opensearch/sql/opensearch/planner/rules/LookupPreFilterRule.java`
- `opensearch/src/main/java/org/opensearch/sql/opensearch/planner/rules/OpenSearchIndexRules.java` (registration)

### Test
- `integ-test/src/test/java/org/opensearch/sql/calcite/remote/CalcitePPLLookupIT.java` (testDiscriminatorWithAdditionalFilters)

## Next Steps

1. Implement transformation in `LookupPreFilterRule.onMatch()`:
   ```java
   // Pseudo-code:
   Join join = call.rel(0);
   CalciteLogicalIndexScan leftScan = call.rel(1);
   CalciteLogicalIndexScan rightScan = call.rel(2);

   // Estimate right side cardinality
   double rightRowCount = mq.getRowCount(rightScan);
   if (rightRowCount > MAX_TERMS_FOR_OPTIMIZATION) return;

   // Extract join key field name
   String joinKeyField = extractJoinKeyFromCondition(join.getCondition());

   // Pre-execute right scan (lookup)
   List<Object> joinKeyValues = executeAndExtractKeys(rightScan, joinKeyField);

   // Create new left scan with terms filter
   CalciteLogicalIndexScan newLeftScan = leftScan.copy();
   newLeftScan.pushDownContext.add(
       PushDownType.TERMS_FILTER,
       new TermsFilterDigest(joinKeyField, joinKeyValues),
       (OSRequestBuilderAction) requestBuilder ->
           requestBuilder.pushDownFilterForCalcite(
               QueryBuilders.termsQuery(joinKeyField, joinKeyValues)
           )
   );

   // Create new join with optimized scans
   Join newJoin = join.copy(..., newLeftScan, rightScan);
   call.transformTo(newJoin);
   ```

2. Add method to execute scans during planning (tricky - might need to refactor)

3. Run integration test to verify:
   - Rule triggers on test query
   - Terms filter is pushed down
   - Correct results returned
   - Performance improvement (compare explain plans)

## Design Decisions

### When to Apply
- Cardinality threshold: < 100 distinct join keys (configurable)
- Only LEFT JOINs (lookup pattern)
- Only simple equality join conditions
- Right side has discriminator + filters

### Fallback
If pre-execution fails or cardinality too high:
- Return without transformation
- Standard hash join will be used

### Future Enhancements
- Cost-based threshold tuning
- Multi-column join keys support
- Batch multiple terms queries for large key sets
- Statistics collection for better estimates
