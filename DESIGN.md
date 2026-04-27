# Design Overview

## Why Python instead of Scala?

The original framework used Scala with ScalaCheck for property-based testing. This implementation uses Python for several reasons:

1. **Lower barrier to entry**: Python has broader adoption in data/testing communities
2. **Faster iteration**: Simpler syntax for rapid prototyping
3. **Better OpenSearch integration**: `opensearch-py` is the official client
4. **Hypothesis library**: Excellent property-based testing support
5. **PPL focus**: Original was SQL-heavy; this targets PPL where bugs are higher value

## Core Architecture

### 1. Data Generation (`datagen/`)

**Context-Aware Schema Generation**

Unlike random fuzzing, we generate schemas first, then queries that respect the schema. This ensures:
- All queries are syntactically valid
- Field references match actual fields
- Type constraints are respected (e.g., arithmetic only on numeric fields)

```python
context = IndexContext(
    name="test_index_0",
    fields=[
        Field("id", FieldType.INTEGER),
        Field("category", FieldType.KEYWORD),
        Field("value", FieldType.DOUBLE)
    ]
)
```

**Benefits:**
- Higher query success rate (fewer parse errors)
- Targets semantic bugs, not syntax bugs
- Reproducible: same seed = same schemas

### 2. Query Generation (`generators/`)

**Recursive Predicate Generation**

Predicates are built recursively with bounded depth:

```python
predicate = "(field_0 > 50 AND field_1 = 'blue') OR field_2 != 10"
```

Depth limiting prevents exponential explosion while ensuring complexity.

**Schema-Constrained Generation**

Generator only produces valid operations:
- Arithmetic: `field_0 + field_1` (only for numeric fields)
- Comparison: `field_x > 5` (only for comparable types)
- Grouping: `by category` (only for low-cardinality fields)

### 3. Properties (`properties/`)

Properties define **invariants that must always hold**.

#### Ternary Logic Partitioning (TLP)

The most powerful property from database testing literature.

**Invariant:** For any query `q` and predicate `p`:

```
UNION(q WHERE p, q WHERE NOT p, q WHERE p IS NULL) == q
```

**Why it works:** Boolean logic has exactly 3 states (true/false/null).

**What it finds:**
- NULL handling bugs in WHERE clauses
- Incorrect predicate evaluation
- Missing rows in results
- Boolean logic short-circuiting errors

**Example bug:** `WHERE a > 5 OR b = 3` incorrectly filters out rows where `b=3` and `a IS NULL`.

#### Aggregation Conservation

**Invariant:** Sum of grouped aggregations equals ungrouped total.

```sql
SUM(stats sum(x) by category) == stats sum(x)
```

**What it finds:**
- GROUP BY dropping rows
- Incorrect aggregation pushdown
- Missing NULL group handling

#### Sorting Invariants

**Invariant 1:** Sort produces ordered output.

**Invariant 2:** `sort -field | head 1` == `stats max(field)`

**What it finds:**
- Sort implementation bugs
- Comparison operator errors
- Head/tail off-by-one errors

#### Differential Testing

**Invariant:** Semantically equivalent queries produce identical results.

```ppl
# These should be equivalent:
source=idx | where a=1 | where b=2
source=idx | where a=1 AND b=2

# These should be equivalent:
source=idx | eval x=a+b | where x>5
source=idx | where a+b>5
```

**What it finds:**
- Query optimization bugs
- EVAL materialization issues
- Predicate pushdown errors

### 4. Execution (`runner/`)

**Randomized Test Execution**

```python
for i in range(iterations):
    context = random.choice(contexts)  # Pick random index
    property.check(context, client)     # Test property
```

**Benefits:**
- Statistical coverage across many schemas
- Finds corner cases that manual tests miss
- Seeds enable reproducing failures

## Comparison to Original Framework

| Aspect | Original (Scala) | This (Python) |
|--------|------------------|---------------|
| **Language** | Scala 3 | Python 3.11+ |
| **PBT Library** | ScalaCheck | Hypothesis (planned) |
| **Focus** | SQL (80%) + PPL (20%) | PPL (90%) + SQL (10%) |
| **Grammar** | Reimplemented SQL grammar | Use OpenSearch PPL directly |
| **Client** | Custom HTTP | opensearch-py |
| **Properties** | TLP, PQS, NoREC | TLP, Sorting, Aggregation, Differential |

## Key Design Decisions

### Why Not Use the Grammar Files?

The original framework reverse-engineered SQL grammar to generate queries. This approach:
- **Pros:** Guarantees syntactic validity, explores entire grammar
- **Cons:** Complex, requires parser knowledge, hard to constrain

This implementation generates queries **programmatically**:
- **Pros:** Simpler, easier to add semantic constraints, faster to iterate
- **Cons:** May miss obscure syntax corners

**Trade-off:** We accept missing some syntax bugs to focus on higher-value semantic bugs.

### Why Store Schema Separately?

Instead of inferring schema from OpenSearch, we **generate and track it explicitly**.

**Benefits:**
- Know field types before querying
- Can generate type-aware queries
- Reproducible from seed alone
- No OpenSearch round-trip during generation

### Property Independence

Each property implementation is **fully independent**:
- Own query generation logic
- Own result validation
- Own failure reporting

**Benefits:**
- Easy to add new properties
- Can run properties in parallel (future)
- Failures isolated to specific properties

## Current Limitations

1. **No Hypothesis integration yet**: Using manual randomization instead of Hypothesis strategies
2. **Limited PPL command coverage**: Currently tests WHERE, STATS, SORT, FIELDS, EVAL
3. **No complex types**: Arrays, nested objects, multi-value fields not yet supported
4. **Single-index only**: No JOIN, LOOKUP, or multi-index testing
5. **Synchronous execution**: Could parallelize property checks

## Future Extensions

### Add More Properties

- **NoREC** (Non-Recoverable Error Chaining): Queries shouldn't crash after valid operations
- **PQS** (Pivoted Query Synthesis): Generate queries from result sets
- **Commutativity**: `a AND b` == `b AND a`, `a + b` == `b + a`
- **Idempotency**: `| dedup | dedup` == `| dedup`

### Expand PPL Coverage

Priority commands (from 52 available):
- **JOIN**: Most complex, high bug potential
- **LOOKUP**: Similar to JOIN
- **PARSE/GROK**: String parsing logic
- **FILLNULL**: NULL handling edge cases
- **MULTISEARCH/UNION**: Multi-index operations

### Hypothesis Integration

Replace manual RNG with Hypothesis strategies:

```python
from hypothesis import given, strategies as st

@given(st.integers(), st.sampled_from(['=', '>', '<']))
def test_comparison(value, op):
    # Property test using Hypothesis
    ...
```

**Benefits:**
- Automatic shrinking (minimal failing case)
- Better statistical distribution
- Integrated reporting

### Mutation Testing

Apply small mutations to known-good queries:
- Swap AND/OR
- Change operators (>/>=)
- Reorder clauses
- Add/remove parentheses

Check that results change in expected ways.

### Regression Suite

When bugs are found:
1. Record the failing query
2. Add to regression tests
3. Verify fix
4. Keep as permanent test

Build a corpus of real bugs found by the framework.

## Performance Considerations

### Index Creation

Creating indices is slow. Optimizations:
- Reuse indices across iterations
- Smaller doc counts for fast properties
- Bulk insert for initial data

### Query Execution

PPL queries can be slow. Strategies:
- Timeout failing queries
- Parallel execution of independent properties
- Cache results for repeated queries

### Result Comparison

Comparing large result sets is expensive:
- Use counts instead of full results where possible
- Hash result sets for equality checks
- Sample large results

## How to Contribute

### Adding a New Property

1. Create `properties/my_property.py`
2. Implement `Property` interface
3. Add to `main.py` property list
4. Document in README
5. Add example test case

### Adding PPL Command Support

1. Update `generators/ppl.py` with new command generator
2. Add field type constraints (if needed)
3. Create property that exercises the command
4. Test against known-good OpenSearch

### Improving Coverage

Run with `--iterations 10000` to find rare bugs. Contribute findings:
- Minimal reproducing query
- Expected vs actual results
- OpenSearch version
- Property that found it

## References

- [SQLancer Paper](https://www.manuelrigger.at/preprints/VLDB2020.pdf) - Original TLP/PQS/NoREC
- [OpenSearch PPL Docs](https://opensearch.org/docs/latest/search-plugins/sql/ppl/index/)
- [Hypothesis Documentation](https://hypothesis.readthedocs.io/)
- [Original RFC Issue](https://github.com/opensearch-project/sql/issues/3220)
