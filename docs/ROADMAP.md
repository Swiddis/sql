# PPL Correctness Framework Roadmap

## Phase 1: Hypothesis Migration (Next)

### 1.1 Core Strategy Migration
- [x] Create `generators/strategies.py` with basic strategies
- [ ] Replace `PPLQueryGenerator` with Hypothesis strategies
- [ ] Add shrinking support to all predicates
- [ ] Benchmark: compare shrinking quality vs ScalaCheck

**Target:** All basic properties use Hypothesis by default

### 1.2 Property Integration
- [ ] Migrate TLP to use `test_scenario` strategy
- [ ] Add `@given` decorators to all properties
- [ ] Configure shrinking settings per property
- [ ] Add example-based testing (`@example` decorator for regressions)

**Deliverable:** Properties automatically shrink to minimal failing cases

### 1.3 Advanced Strategies
- [ ] Recursive command chain generation
- [ ] Constrained strategy composition (valid command ordering)
- [ ] Custom shrinking for command pipelines
- [ ] Strategy for "interesting" values (boundaries, nulls, edge cases)

**Example:**
```python
@given(pipeline=ppl_pipeline(context, max_commands=5))
def test_pipeline_equivalence(pipeline):
    # Test invariants hold for arbitrary pipelines
    ...
```

---

## Phase 2: Complex Type Support

### 2.1 Nested Objects
- [x] Define `ComplexField` and `NestedField` types
- [ ] Update `IndexContext` to support nested schemas
- [ ] Generate nested object test data
- [ ] Add nested field access to query generators
- [ ] Create TLP property for nested predicates

**Test:** `user.age > 21 AND user.city = 'Seattle'`

### 2.2 Arrays/Multi-Value Fields
- [x] Define array field types (ARRAY_INT, ARRAY_STRING)
- [ ] Generate array test data
- [ ] Add array function support to predicates:
  - `json_array_length(field) > 0`
  - `array_contains(field, value)`
  - Element access: `field[0]`
- [ ] Array-specific properties:
  - Length conservation
  - Contains equivalence

**Test:** `json_array_length(tags) = 3` partitions correctly

### 2.3 Timestamp Functions
- [x] Add TIMESTAMP field type
- [ ] Generate timestamp test data (various formats)
- [ ] Add date extraction functions:
  - `YEAR(ts)`, `MONTH(ts)`, `DAY(ts)`
  - `HOUR(ts)`, `MINUTE(ts)`
  - `DAYOFWEEK(ts)`, `DAYOFYEAR(ts)`
- [ ] Timestamp-specific properties:
  - Date part extraction correctness
  - Timezone handling
  - DATE_FORMAT equivalence

**Test:** `SUM(stats count() by YEAR(timestamp)) = stats count()`

### 2.4 GeoPoint Support (if PPL supports)
- [ ] Define GEO_POINT field type
- [ ] Generate geo test data
- [ ] Add geo functions:
  - `geo_distance(point, lat, lon)`
  - Point field access: `location.lat`, `location.lon`
- [ ] Geo-specific properties:
  - Distance triangle inequality
  - Bounding box containment

---

## Phase 3: Command Chain Complexity

### 3.1 Pipeline Properties
- [x] Filter commutativity
- [x] Dedup idempotence
- [x] EVAL field independence
- [ ] Head/Tail composition
- [ ] Rename/Fields interaction
- [ ] WHERE pushdown equivalence

**Test:** `| where a | eval x=b | where x` vs `| eval x=b | where a | where x`

### 3.2 Multi-Stage Aggregations
- [ ] STATS after STATS (nested aggregation)
- [ ] EVENTSTATS (windowed aggregation)
- [ ] STREAMSTATS (running aggregation)
- [ ] TIMECHART properties

**Test:** `| stats count() by field | stats sum(count)` correctness

### 3.3 Rare Commands
- [ ] RARE/TOP equivalence properties
- [ ] PARSE/REGEX/GROK extraction correctness
- [ ] FILLNULL behavior
- [ ] MVEXPAND/FLATTEN for arrays
- [ ] TRANSPOSE properties

**Test:** `| rare field limit=10` vs `| stats count() by field | sort count | head 10`

---

## Phase 4: Join & Multi-Index Testing

### 4.1 JOIN Properties
- [ ] Generate multi-index contexts
- [ ] INNER JOIN conservation
- [ ] LEFT/RIGHT JOIN row preservation
- [ ] Join key correctness
- [ ] Cross product size validation

**Test:** `| join ON key` result count matches relational algebra

### 4.2 LOOKUP Testing
- [ ] Lookup table generation
- [ ] Lookup field mapping correctness
- [ ] Missing key handling

### 4.3 Multi-Index Operations
- [ ] UNION properties (no duplicates, order independence)
- [ ] APPEND properties (row preservation)
- [ ] MULTISEARCH result combination

---

## Phase 5: Advanced Properties

### 5.1 NoREC (Non-Recoverable Error Chaining)
Test that queries shouldn't crash after valid operations.

```python
# Valid query prefix shouldn't cause error in suffix
source=idx | where valid_predicate | <random_suffix>
```

**Finds:** Parser bugs, optimizer crashes, unexpected error states

### 5.2 PQS (Pivoted Query Synthesis)
Generate queries from result sets to find inconsistencies.

1. Execute query Q1 → results R
2. Generate query Q2 that should produce R
3. Verify Q2 produces R

**Finds:** Result set inconsistencies, inverse operation bugs

### 5.3 Query Plan Differential (DQP)
Compare execution plans and results for equivalent queries.

```python
# Get execution plan
EXPLAIN source=idx | where a=1 | where b=2

# Compare to equivalent form
EXPLAIN source=idx | where a=1 AND b=2
```

**Finds:** Query optimizer bugs, incorrect plan equivalence

### 5.4 Mutation Testing
Apply small mutations to known-good queries and verify results change correctly.

```python
# Original
source=idx | where a > 5

# Mutate operator
source=idx | where a >= 5  # Should have ≥ results

# Mutate threshold  
source=idx | where a > 6   # Should have ≤ results
```

**Finds:** Boundary bugs, operator implementation errors

---

## Phase 6: Integration & Tooling

### 6.1 Regression Suite
- [ ] Save minimal failing queries when bugs found
- [ ] Automatic regression test generation
- [ ] Tag bugs by PPL version
- [ ] Track bug fix verification

**Structure:**
```
regressions/
  issue-1234.py  # Auto-generated from bug report
  tlp-failure-2024-01.py
```

### 6.2 Coverage Tracking
- [ ] Track which PPL commands tested
- [ ] Track which field type combinations tested
- [ ] Generate coverage report
- [ ] Identify untested command combinations

**Output:**
```
Command Coverage: 42/52 (80%)
✓ WHERE, STATS, SORT, FIELDS, EVAL
✗ PARSE, GROK, KMEANS, TRENDLINE
```

### 6.3 Performance Optimization
- [ ] Parallel property execution
- [ ] Index reuse across iterations
- [ ] Query result caching
- [ ] Fast-fail on known bugs

### 6.4 CI Integration
- [ ] GitHub Actions workflow
- [ ] Nightly runs with high iteration count
- [ ] Bug report auto-filing
- [ ] Slack/email notifications on failure

**Workflow:**
```yaml
name: PPL Correctness
on:
  schedule:
    - cron: '0 2 * * *'  # Nightly at 2am
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Start OpenSearch
        run: docker-compose up -d
      - name: Run tests
        run: python main.py --all --iterations 1000
```

---

## Phase 7: Documentation & Adoption

### 7.1 User Guide
- [ ] Comprehensive examples for each property
- [ ] Tutorial: adding a new property
- [ ] Tutorial: adding a new PPL command
- [ ] Troubleshooting guide

### 7.2 Developer Docs
- [ ] Architecture deep-dive
- [ ] Strategy composition guide
- [ ] Shrinking behavior explanation
- [ ] Performance tuning tips

### 7.3 Research Paper
- [ ] Compare to SQLancer approach
- [ ] Bug findings analysis
- [ ] Novel properties for PPL
- [ ] Submit to SIGMOD/VLDB

---

## Metrics & Success Criteria

### Short-term (3 months)
- [x] v0 implementation with basic properties
- [ ] Hypothesis integration complete
- [ ] 5+ properties implemented
- [ ] 10+ bugs found and reported
- [ ] Nested object support

### Medium-term (6 months)
- [ ] Full complex type support
- [ ] 15+ properties implemented
- [ ] JOIN testing operational
- [ ] 50+ bugs found
- [ ] Regression suite with 20+ cases

### Long-term (1 year)
- [ ] All 52 PPL commands covered
- [ ] 30+ properties
- [ ] 100+ bugs found
- [ ] CI integration
- [ ] Adopted by OpenSearch team
- [ ] Research paper published

---

## Known Challenges

### Technical
1. **Hypothesis shrinking for complex pipelines**
   - Challenge: Multi-command chains hard to shrink
   - Solution: Custom shrinker that preserves command validity

2. **OpenSearch query timeouts**
   - Challenge: Complex queries may timeout
   - Solution: Configurable timeout, skip slow queries

3. **Result set comparison**
   - Challenge: Large results expensive to compare
   - Solution: Hash-based comparison, sampling

### Organizational
1. **Bug report overload**
   - Challenge: Finding too many bugs
   - Solution: Triage by severity, batch reporting

2. **Regression test maintenance**
   - Challenge: Tests break on intentional changes
   - Solution: Version-specific tests, clear ownership

### Research
1. **Novel properties for PPL**
   - Challenge: PPL is different from SQL
   - Solution: Study PPL-specific semantics, consult docs

2. **Statistical significance**
   - Challenge: How many iterations needed?
   - Solution: Run sensitivity analysis, track bug discovery rate

---

## Next Actions

**Immediate (This Week):**
1. Install Hypothesis: `pip install hypothesis`
2. Run basic TLP tests to verify OpenSearch integration
3. Start migrating one property to Hypothesis

**Short-term (This Month):**
1. Complete Hypothesis migration for all v0 properties
2. Add nested object support
3. Implement 3 new properties (pipeline tests)
4. Document first bugs found

**Ask for Help:**
- Review PPL grammar for unsupported features
- Get OpenSearch test cluster access
- Identify high-priority commands to test
- Review property implementations for correctness
