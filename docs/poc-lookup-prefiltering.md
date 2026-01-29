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
     - ✅ Check if cardinality < threshold (100,000)
     - ✅ Pre-execute right scan and extract distinct join key values
     - ✅ Create `TermsFilterDigest` and inject into left scan's `PushDownContext`
     - ✅ Push down terms query using `QueryBuilders.termsQuery()`
     - ✅ Return transformed plan with optimized left scan
   - ✅ Implemented `preExecuteAndExtractJoinKeys()` helper method:
     - Creates OpenSearchIndexEnumerator for the right scan
     - Iterates through results and collects distinct join key values
     - Filters out null values
     - Safety check to prevent exceeding threshold

5. **Filter Extraction for Pre-Execution** (`sql-j4z`) - ✅ COMPLETE
   - **Root Cause Fixed**: Filters on lookup columns weren't pushed to scan before pre-execution
   - ✅ Changed rule pattern to match `LogicalFilter -> LogicalProject -> Join`
   - ✅ Implemented `extractAndPushRightSideFilters()`:
     - Maps filter conditions through project to join output space
     - Extracts conditions that only reference right-side fields
     - Shifts indices to be relative to right scan
     - Pushes filters using `PredicateAnalyzer` before pre-execution
   - ✅ Fixed pre-execution to use filtered scan (reduced from 30 to 2 keys in test case)

6. **LIMIT Pushdown for LEFT JOIN** (`sql-7o2`) - ✅ COMPLETE
   - ✅ Created `LimitLeftJoinRule` to push LIMIT to probe side of LEFT JOIN
   - ✅ Pattern: `LogicalSort -> LogicalFilter -> LogicalProject -> Join`
   - ✅ Pushes limit to left scan while preserving `EnumerableLimit` operator
   - ✅ Works in combination with pre-filtering optimization
   - ✅ Used existing `pushDownFilterForCalcite()` method for terms filter push-down

## Performance Results

### Benchmark: Filtered Lookup with LIMIT
**Query**:
```ppl
source=request_logs
| lookup dim_lookup.host host_key append service_name, environment, region
| where service_name = "payment-service" and environment = "prod"
| head
| fields request_id, region
```

**Before Optimization** (without pre-filtering):
```
Time (mean ± σ):     16.791 s ±  0.080 s
Range (min … max):   16.654 s … 16.948 s
```
- Full scan of request_logs (millions of rows)
- In-memory hash join with full dataset
- Post-join filtering

**After Optimization** (with pre-filtering + LIMIT pushdown):
```
Time (mean ± σ):     69.8 ms ±  8.6 ms
Range (min … max):   57.0 ms … 86.8 ms
```
- Pre-executed lookup: 2 distinct host_keys extracted
- Terms filter pushed to request_logs: `host_key IN (1, 3)`
- LIMIT 10 pushed to filtered scan
- Join operates on ~20 rows instead of millions

**Speedup: ~240x** (16.8s → 69.8ms)

### Optimization Breakdown
From integration test logs:
```
[LookupPreFilterRule] Extracting right-side filters from LogicalFilter above join
[LookupPreFilterRule] Extracted right-side filter: =($2, 'payment-service')
[LookupPreFilterRule] Extracted right-side filter: =($0, 'prod')
[LookupPreFilterRule] Pushing combined filter to right scan: AND(=($2, 'payment-service'), =($0, 'prod'))
[LookupPreFilterRule] Pre-execution complete: 2 rows processed, 2 distinct keys found
[LookupPreFilterRule] Distinct keys: [1, 3]
[LimitLeftJoinRule] Pushing limit 10 offset 0 to left scan
```

Key insight: Filter extraction reduced pre-execution from 30 keys (all discriminator matches) to 2 keys (discriminator + WHERE predicates), enabling accurate optimization.

## Key Files

### Infrastructure
- `opensearch/src/main/java/org/opensearch/sql/opensearch/storage/scan/context/TermsFilterDigest.java`
- `opensearch/src/main/java/org/opensearch/sql/opensearch/storage/scan/context/PushDownType.java` (added TERMS_FILTER)
- `opensearch/src/main/java/org/opensearch/sql/opensearch/storage/scan/AbstractCalciteIndexScan.java` (estimateRowCount)

### Rules
- `opensearch/src/main/java/org/opensearch/sql/opensearch/planner/rules/LookupPreFilterRule.java` (pre-filtering optimization)
- `opensearch/src/main/java/org/opensearch/sql/opensearch/planner/rules/LimitLeftJoinRule.java` (LIMIT pushdown for LEFT JOIN)
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

### Current Limitations and Gaps

#### Pattern Matching Restrictions
1. **Join Type**: Only LEFT JOINs are optimized (lookup pattern)
   - INNER JOINs converted from LEFT JOIN by Calcite are handled correctly
   - Other join types (RIGHT, FULL OUTER) not supported

2. **Join Condition**: Only simple equality conditions (e.g., `left.key = right.key`)
   - Multi-column join keys not supported
   - Complex join predicates (OR, <>, LIKE) not supported

3. **Filter Placement**: Filters must be in specific positions:
   - ✅ Discriminator filters on lookup table
   - ✅ WHERE predicates after lookup (extracted by `extractAndPushRightSideFilters()`)
   - ❌ Filters within subqueries or complex expressions
   - ❌ Filters on join results (computed columns)

4. **Cardinality Threshold**: Hard-coded at 100,000 distinct keys
   - No dynamic adjustment based on data distribution
   - May apply optimization when not beneficial (overestimate)
   - May skip optimization when beneficial (underestimate)

#### Architectural Concerns
1. **Pre-Execution During Planning**:
   - Violates Calcite's separation between planning and execution
   - Adds latency to query planning (~10-50ms for small lookups)
   - Non-deterministic plans (depends on current data state)
   - Breaks plan caching and reproducibility
   - See `lookup-optimization-alternatives.md` for architectural analysis

2. **Error Handling**:
   - Query failures during pre-execution silently skip optimization
   - No visibility into why optimization didn't apply
   - Network failures during planning can slow down all queries

3. **Infinite Loop Prevention**:
   - Rule checks for existing `TERMS_FILTER` to prevent re-application
   - This is a code smell indicating architectural mismatch

#### Known Edge Cases
1. **Text Field Handling**: Uses `.keyword` subfield for terms queries
   - Assumes keyword subfield exists
   - May fail silently if mapping doesn't have keyword subfield

2. **NULL Values**: Filtered out during pre-execution
   - Correct for inner joins but may affect LEFT JOIN semantics
   - Not tested with outer join null-generating cases

3. **Large Key Sets**: Safety threshold prevents optimization
   - No fallback strategy for medium-sized key sets (1K-100K)
   - Could use batch terms queries or alternative strategies

4. **Filter Extraction Complexity**:
   - Only extracts AND conjunctions referencing right-side fields
   - OR conditions not supported
   - Subquery filters not supported
   - Filter must be directly above join (no intervening operators except project)

### Gaps and Future Work

#### High Priority (Production Hardening)
1. **Architectural Redesign**: See `lookup-optimization-alternatives.md`
   - Option 1: Heuristic-based estimation (no pre-execution)
   - Option 2: Adaptive execution (runtime decision) ⭐ Recommended
   - Option 3: Statistics-based estimation (background collection)

2. **Cost Model**:
   - Measure actual pre-execution overhead
   - Add heuristics for when optimization is beneficial
   - Consider main table size, filter selectivity, network latency

3. **Better Cardinality Estimation**:
   - Analyze filter selectivity without execution
   - Use index statistics when available
   - Discriminator-specific selectivity heuristics

4. **Error Handling and Observability**:
   - Metrics: optimization applied, skipped, failed
   - Debug logging: why optimization did/didn't apply
   - Graceful degradation on failures

#### Medium Priority (Generalization)
1. **Multi-Column Join Keys**:
   - Composite terms queries or multiple terms filters
   - More complex join predicates

2. **Complex Filter Patterns**:
   - OR conditions (pre-execute multiple filter combinations)
   - Subquery filters
   - Filters with expressions on lookup columns

3. **Alternative Join Strategies**:
   - Batch terms queries for medium cardinality (1K-100K keys)
   - Bloom filter push-down for very large key sets
   - Index intersection for multiple join keys

4. **Statistics Collection**:
   - Background collection of discriminator cardinalities
   - Cache for frequent lookup patterns
   - Automatic refresh on index updates

#### Low Priority (Nice to Have)
1. **User Hints**: Allow manual control (`/*+ PREFILTER */`)
2. **Query Plan Caching**: Cache pre-execution results for repeated queries
3. **Asynchronous Pre-execution**: Parallel planning operations
4. **INNER JOIN Direct Support**: Detect and optimize direct inner joins

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
