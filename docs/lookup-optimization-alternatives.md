# Lookup Join Optimization: Architectural Analysis and Alternatives

## Executive Summary

**Status**: POC ✅ **VALIDATED** - 240x speedup achieved (16.8s → 69.8ms)

**Current Approach**: Pre-execution during planning (violates Calcite conventions but extremely effective)

**Recommendation**:
- **Short-term**: Keep current approach, add observability and safeguards
- **Medium-term**: Migrate to adaptive execution (runtime decision)
- **Long-term**: Add statistics-based estimation for frequent patterns

**Key Insight**: The 240x performance improvement justifies accepting architectural trade-offs for now, with a clear migration path to more principled implementations.

## Problem Summary

The current POC (`LookupPreFilterRule`) optimizes lookup joins by pre-executing the lookup scan during Calcite's logical planning phase to extract join keys and push them as a terms filter to the main table. While this provides accurate cardinality information, it violates Calcite's architectural separation between planning and execution.

## Current Approach: Pre-Execution During Planning

### Implementation
`LookupPreFilterRule.onMatch()` (lines 225-268):
- Creates an `OpenSearchIndexEnumerator` during planning
- Executes the lookup query to extract distinct join keys
- Injects these keys as a terms filter into the main table scan
- Returns a transformed plan with the optimized scan

### Architectural Concerns

1. **Violates Separation of Concerns**: Calcite design separates planning (analysis, estimation, optimization) from execution (actual data fetching). Pre-executing queries during planning crosses this boundary.

2. **Non-Deterministic Planning**: The same logical query produces different physical plans based on current data state. This breaks plan caching, reproducibility, and testing.

3. **Planning Time Overhead**: Every query incurs an additional OpenSearch round-trip during planning, even if the optimization doesn't apply.

4. **Error Handling Complexity**: Query failures during planning become optimization failures, requiring special error handling paths.

5. **Caching Needed to Prevent Loops**: The rule needs to check if a terms filter already exists (lines 73-81) to prevent infinite rule application - a code smell indicating architectural mismatch.

6. **Testing Difficulty**: Plans aren't reproducible with test fixtures since they depend on actual data at planning time.

## How Calcite Normally Works

### RelMetadataQuery: Heuristics, Not Real Data
Calcite's `RelMetadataQuery` provides **statistical estimates** using heuristics, not actual data:

```java
// AbstractCalciteIndexScan.java:141-143
case FILTER, SCRIPT ->
  NumberUtil.multiply(rowCount,
    RelMdUtil.guessSelectivity(condition));  // ~15% selectivity heuristic
```

### Standard Pushdown Pattern
All existing pushdown rules follow this pattern:
1. **Match** the pattern (`matches()`)
2. **Estimate** based on structure/heuristics
3. **Transform** the plan (inject push-down operation into `PushDownContext`)
4. **Defer execution** until runtime

Example from `FilterIndexScanRule`:
```java
AbstractRelNode newRel = scan.pushDownFilter(filter);
if (newRel != null) {
  call.transformTo(newRel);  // Transform, don't execute
}
```

The actual filter execution happens later when the enumerator runs.

## Alternative Approaches

### Option 1: Heuristic-Based Cost Estimation (Recommended for Calcite)

**Concept**: Improve cardinality estimation using filter structure analysis, without executing queries.

**Implementation**:
```java
@Override
public void onMatch(RelOptRuleCall call) {
  Join join = call.rel(0);
  CalciteLogicalIndexScan rightScan = call.rel(2);

  // Analyze filter structure without executing
  double estimatedSelectivity = estimateDiscriminatorSelectivity(rightScan);
  double estimatedLookupRows = rightScan.estimateRowCount(mq) * estimatedSelectivity;

  // Only apply if estimated rows < threshold
  if (estimatedLookupRows < MAX_TERMS_FOR_OPTIMIZATION) {
    // Create optimized plan with terms filter hint
    // Actual execution deferred to runtime
    call.transformTo(createOptimizedPlan(...));
  }
}

private double estimateDiscriminatorSelectivity(CalciteLogicalIndexScan scan) {
  PushDownContext ctx = scan.getPushDownContext();
  double selectivity = 1.0;

  for (PushDownOperation op : ctx) {
    if (op.type() == PushDownType.FILTER) {
      RexNode filter = (RexNode) op.digest();

      // Discriminator filter (_lookup = 'host'): high selectivity
      if (isDiscriminatorFilter(filter)) {
        selectivity *= 0.05;  // Assume discriminator reduces to ~5% of rows
      }

      // Equality filters: multiply selectivity
      if (isEqualityFilter(filter)) {
        selectivity *= 0.1;  // Each equality ~10% selectivity
      }
    }
  }

  // Estimate distinct keys: cardinality × selectivity / avg_duplicates
  // For dimension tables, assume low duplication (e.g., 2x)
  return selectivity / 2.0;
}
```

**Advantages**:
- ✅ No execution during planning (fast, deterministic)
- ✅ Follows Calcite architectural patterns
- ✅ Reproducible plans for testing
- ✅ No error handling complexity
- ✅ Still provides join optimization when estimates suggest benefit

**Disadvantages**:
- ❌ Estimates may be inaccurate for unusual data distributions
- ❌ May miss optimization opportunities or apply when not beneficial

**Best For**: Production-ready implementation that follows Calcite conventions

### Option 2: Adaptive Execution (Runtime Decision)

**Concept**: Defer the optimization decision to execution time, choosing join strategy based on actual lookup cardinality.

**Implementation**:
```java
// New join operator that decides strategy at runtime
class AdaptiveLookupJoin extends Join {

  @Override
  public Enumerator<Object[]> execute() {
    // Execute lookup first (small side)
    List<Object[]> lookupResults = executeScan(rightScan);

    // Runtime decision based on actual cardinality
    if (lookupResults.size() < MAX_TERMS_FOR_OPTIMIZATION) {
      // Extract join keys and filter left side
      Set<Object> joinKeys = extractJoinKeys(lookupResults, rightJoinField);

      // Push terms filter to left scan before executing
      leftScan.addRuntimeTermsFilter(leftJoinField, joinKeys);

      LOG.info("Applied runtime lookup optimization: {} distinct keys", joinKeys.size());
    } else {
      LOG.info("Skipped lookup optimization: {} rows exceeds threshold",
               lookupResults.size());
    }

    // Execute main table scan (now filtered if optimization applied)
    Enumerator<Object[]> leftEnumerator = leftScan.execute();

    // Proceed with hash join
    return new HashJoinEnumerator(leftEnumerator, lookupResults, joinCondition);
  }
}
```

**Planning Phase**:
```java
@Override
public void onMatch(RelOptRuleCall call) {
  // Always transform to AdaptiveLookupJoin when pattern matches
  // No execution, no estimation needed
  call.transformTo(new AdaptiveLookupJoin(join, leftScan, rightScan));
}
```

**Advantages**:
- ✅ Decision based on actual data (most accurate)
- ✅ No planning overhead
- ✅ Handles dynamic data well
- ✅ Aligns with modern query engine trends (Spark AQE, Presto, DuckDB)
- ✅ Can collect statistics for future cost model improvements

**Disadvantages**:
- ❌ Requires new join operator implementation
- ❌ More complex execution flow
- ❌ May execute lookup scan before knowing if optimization will help

**Best For**: Production system where data distributions are unpredictable

### Option 3: Statistics-Based Estimation

**Concept**: Collect and cache statistics about lookup tables to inform planning decisions.

**Implementation**:
```java
// Background statistics collector (at session start or periodically)
class LookupStatisticsCache {
  // Maps: "index.discriminator" -> estimated distinct keys
  Map<String, StatEntry> stats = new ConcurrentHashMap<>();

  void collectStats(String index, String discriminator) {
    // Execute: SELECT COUNT(DISTINCT key_field) FROM index WHERE _lookup = discriminator
    int distinctKeys = queryDistinctKeys(index, discriminator);
    stats.put(index + "." + discriminator, new StatEntry(distinctKeys, Instant.now()));
  }

  int getEstimatedDistinctKeys(String index, String discriminator, List<RexNode> filters) {
    StatEntry entry = stats.get(index + "." + discriminator);
    if (entry == null || entry.isStale()) {
      return DEFAULT_ESTIMATE;
    }

    // Adjust base count by filter selectivity
    int baseCount = entry.distinctKeys;
    double filterSelectivity = estimateFilterSelectivity(filters);
    return (int)(baseCount * filterSelectivity);
  }
}

// Use in planning
@Override
public void onMatch(RelOptRuleCall call) {
  int estimatedKeys = lookupStatsCache.getEstimatedDistinctKeys(
      index, discriminator, extractFilters(rightScan));

  if (estimatedKeys < MAX_TERMS_FOR_OPTIMIZATION) {
    call.transformTo(createOptimizedPlan(...));
  }
}
```

**Advantages**:
- ✅ Accurate estimates without per-query execution
- ✅ Fast planning (cache lookup only)
- ✅ Works well for dimension tables (typically small and static)
- ✅ Standard approach in OLAP systems

**Disadvantages**:
- ❌ Requires background statistics collection
- ❌ Stats may become stale for dynamic data
- ❌ Additional infrastructure to maintain

**Best For**: Systems with relatively static lookup/dimension tables

### Option 4: User Hints (Pragmatic Fallback)

**Concept**: Allow users to manually specify when optimization should apply.

**Syntax**:
```ppl
source = request_logs
| lookup /*+ PREFILTER */ dim_lookup.host host_key
| where service_name = 'payment-service'
```

**Advantages**:
- ✅ Users know their data best
- ✅ Simple to implement
- ✅ No estimation or execution complexity
- ✅ Good for edge cases and experimentation

**Disadvantages**:
- ❌ Requires user expertise
- ❌ Manual tuning burden
- ❌ Poor user experience for automatic optimization

**Best For**: POC/experimental features, expert users, edge cases

## Performance Validation

### Phase 1: Fix POC ✅ COMPLETED
**Goal**: Get working POC to measure actual performance gains

**Completed Work**:
1. ✅ Fixed filter extraction bug (filters on lookup columns weren't pushed before pre-execution)
2. ✅ Implemented `extractAndPushRightSideFilters()` to properly extract WHERE predicates
3. ✅ Added LIMIT pushdown rule (`LimitLeftJoinRule`) for LEFT JOIN queries
4. ✅ Measured actual performance improvement on realistic workload

**Performance Results** (Filtered lookup with LIMIT query):
```
Before:  16.791s ± 0.080s  (full table scan + hash join)
After:   69.8ms ± 8.6ms    (pre-filtered + limit pushed)
Speedup: ~240x
```

**Key Insight**: The optimization provides **dramatic speedup** (240x) for the target use case:
- Selective discriminator + additional filters
- Small lookup result set (< 100 distinct keys)
- Combined with LIMIT pushdown

**Root Cause of Initial Bug**: Pre-execution was extracting all discriminator matches (30 keys) instead of filtered matches (2 keys), causing excessive main table filtering. Filter extraction fixed this by pushing WHERE predicates to lookup scan before pre-execution.

**Empirical Validation**: ✅ The optimization is **highly effective** for dimensional analytics lookup patterns. This justifies investment in architectural improvements for production deployment.

### Phase 2: POC Alternative Approaches
**Goal**: Validate architectural alternatives with prototypes

**Track 1 - Heuristic-Based (sql-xxx)**:
- Implement Option 1 (heuristic estimation)
- Compare plan quality vs. pre-execution approach
- Measure planning time improvement

**Track 2 - Adaptive Execution (sql-yyy)**:
- Implement Option 2 (runtime decision)
- Compare with pre-execution and heuristic approaches
- Measure end-to-end query latency

**Deliverable**: Performance comparison showing:
- Planning time overhead
- Query execution time
- Optimization accuracy (how often right decision made)

### Phase 3: Production Implementation
**Goal**: Choose and implement production-ready approach based on empirical data

**Given Performance Results** (240x speedup):
The optimization is extremely valuable for the target use case. The architectural concerns (pre-execution during planning, non-deterministic plans) are significant but the performance gains justify keeping this approach for now while planning migration path.

**Recommended Strategy**:

#### Short-Term (Current POC → Initial Production)
**Keep current pre-execution approach** with improvements:
1. ✅ Already working with 240x speedup
2. Add comprehensive metrics and observability:
   - Count: optimizations applied/skipped/failed
   - Timing: pre-execution overhead, total query time
   - Cardinality: estimated vs actual distinct keys
3. Add query hint to disable optimization if needed: `/*+ NO_PREFILTER */`
4. Document limitations and known edge cases
5. Enable only for specific indices or query patterns (opt-in via config)

**Rationale**:
- The optimization is proven to work extremely well for the target pattern
- Pre-execution overhead (~10-50ms) is negligible compared to 240x speedup
- Architectural concerns are real but don't outweigh massive performance gains
- Can migrate to better architecture incrementally

#### Medium-Term (Production Hardening)
**Implement Option 2 (Adaptive Execution)** as architectural improvement:

**Phase A: Prototype** (`sql-7pz`):
- Create `AdaptiveLookupJoin` operator
- Defer optimization decision to runtime
- Compare performance with pre-execution approach
- Measure: planning time, execution time, optimization accuracy

**Phase B: Hybrid Approach** (if prototype validates):
- Keep pre-execution as default for now
- Use adaptive execution for cases where:
  - Pre-execution fails or times out
  - Estimated cardinality is borderline (80K-120K keys)
  - User explicitly requests via hint
- Collect runtime statistics for Option 3

**Phase C: Full Migration**:
- Make adaptive execution the primary strategy
- Fall back to heuristics when runtime decision is expensive
- Use collected statistics for better cost estimation

#### Long-Term (Advanced Optimization)
**Implement Option 3 (Statistics-Based)** as additional optimization:
- Background collection of discriminator cardinalities
- Cache frequent lookup patterns
- Use statistics to inform both heuristics and runtime decisions
- Periodic refresh to handle data evolution

**Decision Criteria Applied**:
1. ✅ Lookup tables are static and small → Statistics will help (long-term)
2. ✅ Planning time is critical → Will migrate to adaptive (medium-term)
3. ❌ Following Calcite conventions is priority → Accept deviation for now given gains
4. ✅ Data patterns are unpredictable → Adaptive execution is right long-term strategy

**Migration Path**: Pre-execution (now) → Hybrid (medium-term) → Adaptive + Statistics (long-term)

## Key Architectural Insights

1. **Calcite's Philosophy**: Separate planning (analysis, estimation, optimization) from execution (data fetching). Crossing this boundary creates complexity and anti-patterns.

2. **RelMetadataQuery Purpose**: Provides structural/statistical estimates for cost-based optimization, not actual data measurements.

3. **Modern Trend**: Query engines increasingly use adaptive execution (runtime decisions) over pure cost-based optimization, as data distributions are hard to predict.

4. **POC Value**: Current pre-execution approach is valuable for measuring actual performance gains, but shouldn't go to production without architectural redesign.

## References

### Existing Calcite Patterns
- `FilterIndexScanRule`: Standard push-down rule (no execution)
- `AbstractCalciteIndexScan.estimateRowCount()`: Heuristic-based estimation
- `EnumerableIndexScanRule`: Conversion from logical to physical (deferred execution)

### Related Issues
- `sql-p53`: Lookup pre-filtering optimization with discriminators (epic)
- `sql-nrr`: Pre-execute lookup scan and extract join keys (current POC implementation)
- `sql-bij`: Discriminator-based lookups (feature enabler)

### Modern Adaptive Execution Examples
- Spark AQE (Adaptive Query Execution): Runtime join strategy selection
- Presto: Runtime filter push-down based on build side cardinality
- DuckDB: Adaptive hash join with runtime filter injection
