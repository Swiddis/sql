# Lookup Join Optimization: Architectural Analysis and Alternatives

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

## Recommended Implementation Path

### Phase 1: Fix POC (Remove Pre-Execution)
**Goal**: Get working POC to measure actual performance gains

**Approach**: Keep pre-execution for now but fix the bugs:
1. Remove caching check (lines 73-81) that prevents rule re-application
2. Add extensive debug logging to understand execution flow
3. Fix any issues with join key extraction or terms query construction
4. Measure actual performance improvement on realistic workloads

**Rationale**: Need empirical data on performance gains to justify investment in alternatives

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
**Goal**: Choose and implement production-ready approach

**Decision Criteria**:
1. If lookup tables are static and small → **Option 3** (statistics)
2. If planning time is critical → **Option 2** (adaptive execution)
3. If following Calcite conventions is priority → **Option 1** (heuristic estimation)
4. If data patterns are unpredictable → **Option 2** (adaptive execution)

**Likely Best Choice**: Hybrid approach:
- **Primary**: Option 2 (adaptive execution) for accuracy and simplicity
- **Fallback**: Option 1 (heuristic estimation) for cases where runtime decision is expensive
- **Future**: Option 3 (statistics) for frequently-used lookup tables

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
