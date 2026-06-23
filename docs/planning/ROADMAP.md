# PPL Correctness Testing Roadmap

**Goal:** Cover 100% of supported PPL operations from customer query patterns (1.3M SPL queries).

**Current state:**
- Command coverage: 8/52 documented commands (15%)
- Function coverage: ~5% (null checks, comparisons, basic math only)
- Production pattern coverage: ~5% (from test suite analysis)

**Coverage validated:** 72 manual test queries (98% pass rate, 1 real bug found)

---

## Phase 1: Core Commands (High ROI)

These 10 commands cover 70%+ of production patterns.

### Tier 1: Text & Extraction (41k+ occurrences)
- **spath** (41,456) — JSON path extraction
  - Property: spath extraction = equivalent nested field access
  - Known issue: struct input ClassCastException (#existing)
  
- **rex** (8,498) — Regex extraction
  - Property: extracted fields accessible in downstream commands
  - Test: rex | stats, rex | where

### Tier 2: Advanced Aggregation (26k+ occurrences)
- **lookup** (26,037) — Table joins
  - Property: lookup field propagation through pipeline
  - Test: lookup | filter | stats chains
  
- **bin/bucket** (12,388) — Numeric/time bucketing
  - Property: bin output field naming consistency
  - Test: bin | stats, timestamp binning

- **top/rare** (~5k inferred) — Top-N by frequency
  - Property: top N + rare N = full dataset (modulo ties)
  - Equivalent: stats count() by field | sort | head/tail

### Tier 3: Pipeline & Multi-Value (11k+ occurrences)
- **flatten/mvexpand** — Nested/array flattening
  - Property: flatten preserves row count * cardinality
  - Critical for array support (currently disabled)

- **fillnull** — Null value handling
  - Property: fillnull changes null predicates
  - Test: before/after isnull() results

- **timechart** (11,645) — Time-series aggregation
  - SPL-specific but high usage; may map to bin + stats

### Tier 4: Multi-Source (rare but critical)
- **union** — Combine queries
  - Property: UNION row count = sum of parts
  - Test: union | dedup, union | stats

- **join** — Multi-index joins
  - Property: join field propagation
  - Requires multi-index support (not implemented)

---

## Phase 2: Functions (Medium ROI)

15 function categories, focusing on high-frequency patterns.

### String Functions (highest production usage)
From patterns: `eval(func:upper)`, `eval(func:coalesce)`, `eval(func:if)`

- **Conditional:** if, case, coalesce (11,645+ occurrences)
  - Property: if(p, a, b) where p = a; if(p, a, b) where NOT p = b
  - Critical for eval chains

- **String transforms:** upper, lower, concat, substring, split
  - Property: upper(lower(x)) = upper(x)
  - Test: string transform | where | stats

- **Pattern matching:** like, match, regex
  - Property: LIKE consistency with regex equivalent
  - High bug potential (text vs keyword issues)

### Datetime Functions (12,388+ occurrences)
From patterns: `eval(func:strftime)`, `eval(func:strptime)`

- **date_format, date_add, date_diff**
  - Property: date arithmetic round-trips
  - Test: date_add then date_diff = identity

### Aggregation Functions
- **percentile, stddev, var** (statistical category)
  - Property: percentile ordering
  - Extend aggregation.py conservation laws

### Collection Functions
- **mv* functions** (mvcombine, mvexpand, mvjoin)
  - Blocked on array support (#5333)
  - Property: mvexpand | mvcombine = identity (modulo order)

---

## Phase 3: Properties (Completeness)

Extend metamorphic relations to cover new commands.

### New Property Classes

**1. Extraction Equivalence**
- spath vs. dotted notation vs. flatten
- rex vs. grok vs. parse
- Property: different extraction methods = same results

**2. Aggregation Equivalence**  
- top/rare vs. stats count() | sort | head
- timechart vs. bin + stats
- Property: syntactic sugar = explicit form

**3. Multi-Source Conservation**
- union row count = sum
- join size bounds (inner ≤ min, outer ≥ max)

**4. Null Handling**
- fillnull interaction with predicates
- isnull before/after fillnull

**5. Command Commutativity**
- where A | where B = where B | where A
- dedup field1 | dedup field2 ≠ reverse (order matters)
- Identify truly commutative vs. order-dependent

---

## Phase 4: Command Chains (Scale)

Generate composite queries from production patterns.

### Chain Generation Strategy

**Markov-based patterns** (from 1.3M queries):
- Weight by frequency: search|stats (45k) >> search|kmeans (rare)
- Top 100 2-command chains
- Top 50 3-command chains
- Top 20 4+ command chains

**Example high-value chains:**
```
search | spath | search | stats           (32k occurrences)
search | bucket | eval | stats            (12k occurrences)
search | rex | rex | eval | search        (7k occurrences)
search | lookup | search | stats | where  (26k occurrences)
```

**Implementation:**
- `generate_command_chain()` already exists
- Add frequency weights from production data
- Target: 1000 distinct chain patterns → 10k test cases

---

## Phase 5: Differential Testing (Quality)

Compare OpenSearch vs. other engines (if available).

### Strategies
- **Semantic equivalence:** Different query forms, same result
- **Cross-version:** Same query, different OpenSearch versions
- **Cross-backend:** PPL vs. SQL translation (if applicable)

---

## Prioritized Roadmap (6-Month View)

### Month 1-2: Command Extensions
- [ ] Add spath, rex generators (text extraction)
- [ ] Add bin/bucket generator (bucketing)
- [ ] Add top/rare generators (or property: top = stats|sort|head)
- [ ] Extend tlp.py for new commands
- [ ] Target: 20/52 commands (40%)

### Month 3: Function Support  
- [ ] Conditional functions (if, case, coalesce)
- [ ] String functions (upper, lower, concat, substring)
- [ ] Datetime functions (date_format, date_add)
- [ ] Extend eval generator with function catalog
- [ ] Target: 50+ functions across 5 categories

### Month 4: Advanced Properties
- [ ] Extraction equivalence property (spath = nested access)
- [ ] Aggregation equivalence (top = stats|sort|head)
- [ ] Null handling property (fillnull interactions)
- [ ] Multi-command commutativity checks

### Month 5-6: Production Pattern Coverage
- [ ] Markov chain generator from 1.3M query patterns
- [ ] Weight by frequency (top 1000 patterns)
- [ ] Generate 10k test cases from real patterns
- [ ] Measure coverage: % of unique patterns testable
- [ ] Target: 80%+ of high-frequency patterns (10k+ occurrences)

---

## Measuring Progress

### Metrics
1. **Command coverage:** X/52 documented commands
2. **Function coverage:** X/~200 documented functions
3. **Pattern coverage:** % of 1.3M queries whose pattern is generatable
4. **Bug yield:** Bugs found per 1000 property checks

### Milestones
- **M1 (Phase 1 done):** 20 commands, can generate 50% of top-100 patterns
- **M2 (Phase 2 done):** +50 functions, 70% of top-100 patterns
- **M3 (Phase 4 done):** 80% of high-frequency patterns (10k+ occurrences)
- **M4 (100% supported):** Every pattern using only documented commands

---

## Gaps Not Covered (Out of Scope)

**SPL-specific commands** (no PPL equivalent):
- timechart (can map to bin+stats but semantics differ)
- tstats (accelerated datamodel search)
- transaction (event grouping - complex state)
- makeresults, multikv (SPL test utilities)

**Unsupported in OpenSearch PPL:**
- Machine learning commands (ml, kmeans require plugins)
- Advanced analytics (trendline, eventstats)
- Some multi-value commands (nomv, mvcombine specifics)

**Infrastructure limitations:**
- Multi-index joins (join, lookup require cross-index test setup)
- External data sources (requires additional services)

Focus on **supported** commands with **high production usage** first.

---

## Next Immediate Actions

1. **Add spath generator** — simplest high-impact command (41k occurrences)
   - Extend `PPLQueryGenerator.generate_spath_query()`
   - Property: spath path=X.Y = nested field access `X.Y`

2. **Add rex generator** — regex extraction (8k occurrences)  
   - Extend `PPLQueryGenerator.generate_rex_query()`
   - Property: extracted field usable in downstream where/stats

3. **Add conditional functions to eval** — if, case, coalesce (11k+ occurrences)
   - Extend `generate_eval_query()` with function catalog
   - Property: if(p, a, b) WHERE p ≡ a, if(p, a, b) WHERE NOT p ≡ b

4. **Markov chain skeleton** — weighted pattern generation
   - Load production pattern frequencies
   - Extend `generate_command_chain()` with frequency weights
   - Target: top 100 2-command chains

**Estimated effort:** 2-3 weeks for items 1-3, 1 week for item 4.

**Expected bug yield:** 5-10 bugs from 1000 additional test cases (based on 2% bug rate from validation).
