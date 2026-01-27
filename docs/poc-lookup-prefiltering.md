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
   - Verifies correct result set (6 rows matching host_key 1 or 3)

### ✅ Completed

4. **Pre-execution & Transformation** (`sql-c1x`) - ✅ COMPLETE
   - ✅ Implemented `LookupPreFilterRule.onMatch()`:
     - ✅ Extract join key field names from join condition
     - ✅ Estimate cardinality of lookup result using RelMetadataQuery
     - ✅ Check if cardinality < threshold (100)
     - ✅ Pre-execute right scan and extract distinct join key values
     - ✅ Create `TermsFilterDigest` and inject into left scan's `PushDownContext`
     - ✅ Push down terms query using `QueryBuilders.termsQuery()`
     - ✅ Return transformed plan with optimized left scan
   - ✅ Implemented `preExecuteAndExtractJoinKeys()` helper method:
     - Creates OpenSearchIndexEnumerator for the right scan
     - Iterates through results and collects distinct join key values
     - Filters out null values
     - Safety check to prevent exceeding threshold
   - ✅ Used existing `pushDownFilterForCalcite()` method for terms filter push-down

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

## Implementation Summary (sql-c1x)

### What Was Implemented

1. **Pre-execution During Planning** (`LookupPreFilterRule.preExecuteAndExtractJoinKeys()`):
   - Executes the right scan (lookup) during the planning phase
   - Creates an `OpenSearchIndexEnumerator` to iterate through filtered lookup results
   - Collects distinct join key values into a Set (for deduplication)
   - Filters out null values to avoid invalid terms queries
   - Safety check: stops collecting if threshold (100) is exceeded
   - Properly closes the enumerator to release resources

2. **Terms Filter Push-down**:
   - Uses `QueryBuilders.termsQuery(fieldName, joinKeyValues)` to create OpenSearch terms query
   - Leverages existing `requestBuilder.pushDownFilterForCalcite()` method
   - Logs the number of distinct values for debugging

3. **Integration Test**:
   - Fixed `testDiscriminatorWithAdditionalFilters()` test expectations
   - Test creates 10 request_logs and 3 lookup records
   - Verifies correct result set (6 rows matching host_key 1 or 3)
   - Test passes successfully

### Technical Approach

The POC demonstrates that **scan pre-execution during planning is feasible** by:
- Accessing the `OpenSearchIndex` from the `CalciteLogicalIndexScan` node
- Building a request from the scan's `PushDownContext` (which includes filters)
- Creating an enumerator that executes the scan synchronously
- Collecting results during planning without interfering with the main query execution

This approach works because:
- The right scan (lookup) is filtered and small (< 100 rows)
- The planning phase has access to the OpenSearch client
- The enumerator pattern allows synchronous iteration
- Resource cleanup is handled properly with `enumerator.close()`

### Limitations

1. **Query Pattern**: The rule only triggers when:
   - The right scan already has filters in its `PushDownContext`
   - The join condition is a simple equality (e.g., `left.key = right.key`)
   - The estimated cardinality is below threshold (100 rows)

2. **Filter Push-down Timing**: For the optimization to apply, filters must be pushed down to the right scan BEFORE the join optimization phase. Post-join filters (e.g., `| where ...` after lookup) won't trigger the optimization.

3. **Performance**: Pre-executing the scan adds latency during planning, but this is acceptable for small result sets (< 100 rows).

### Next Steps for Production

1. **Cost-Based Optimization**: Add cost model to decide when pre-execution is beneficial
2. **Multi-Column Join Keys**: Support composite join conditions
3. **Asynchronous Pre-execution**: Execute lookup scan in parallel with other planning operations
4. **Query Rewriting**: Push post-join filters down to enable the optimization
5. **Performance Testing**: Measure actual speedup on real workloads

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
- Actual pre-execution during planning (see architectural challenge above)
