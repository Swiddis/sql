# Example Testing Session

This document shows a realistic testing session using the framework.

## Scenario: Testing a Fresh OpenSearch Build

You've just built OpenSearch from main branch and want to check for PPL bugs.

### Step 1: Start OpenSearch

```bash
# Terminal 1: Start OpenSearch
cd ~/workplace/sql
./gradlew run

# Wait for "Node started" message
```

### Step 2: Quick Bug Scan

```bash
# Terminal 2: Run bug-specific tests
cd ~/etc/ppl-correctness
python test_bug_coverage.py --all
```

**Example Output:**
```
2026-04-27 15:30:00 [INFO] 
======================================================================
Testing ARRAYS bugs
======================================================================

2026-04-27 15:30:00 [INFO] Issue #5333:
2026-04-27 15:30:00 [INFO]   Running ArrayGroupByAtomicity...
2026-04-27 15:30:01 [WARNING]     ✗ FAILED with 1 violation(s):
2026-04-27 15:30:01 [WARNING]       - GROUP BY array_int explodes arrays: grouped sum (78) > total (50)
2026-04-27 15:30:01 [WARNING]         Query: source=test_array_properties | stats count() by array_int
2026-04-27 15:30:01 [WARNING]         Expected: 50
2026-04-27 15:30:01 [WARNING]         Actual: 78

2026-04-27 15:30:01 [INFO]   Running ArrayWhereFilterConsistency...
2026-04-27 15:30:02 [INFO]     ✓ PASSED (no violations)

2026-04-27 15:30:02 [INFO]   Running ArraySortDeterminism...
2026-04-27 15:30:03 [INFO]     ✓ PASSED (no violations)

...

2026-04-27 15:30:15 [INFO] 
======================================================================
Testing NESTED bugs
======================================================================

2026-04-27 15:30:15 [INFO] Issue #4906:
2026-04-27 15:30:15 [INFO]   Running NestedFieldAccessNullCheck...
2026-04-27 15:30:16 [WARNING]     ✗ FAILED with 1 violation(s):
2026-04-27 15:30:16 [WARNING]       - Accessing nested field (enabled=false) returns null despite field existing
2026-04-27 15:30:16 [WARNING]         Query: source=test_nested_properties | fields nested_obj.c.d | head 1
2026-04-27 15:30:16 [WARNING]         Expected: Non-null value for nested_obj.c.d
2026-04-27 15:30:16 [WARNING]         Actual: null

...

2026-04-27 15:30:30 [INFO] 
======================================================================
SUMMARY
======================================================================
2026-04-27 15:30:30 [INFO] Properties run: 9
2026-04-27 15:30:30 [WARNING] Total violations: 3
2026-04-27 15:30:30 [WARNING] ✗ Found issues in 3 tests.
```

**Interpretation:** 
- Issue #5333 (arrays) is present: GROUP BY explodes arrays
- Issue #4906 (nested) is present: Nested field access returns null
- Other bugs not detected (possibly fixed)

### Step 3: Deep Dive on a Specific Bug

Let's investigate the array GROUP BY bug more:

```bash
# Run just array tests for faster iteration
python test_bug_coverage.py --category arrays
```

**Manual Verification:**

```bash
# Open another terminal
curl -XPUT 'localhost:9200/test_manual' -H 'Content-Type: application/json' -d '
{
  "mappings": {
    "properties": {
      "id": {"type": "integer"},
      "tags": {"type": "keyword"}
    }
  }
}'

# Insert array data
curl -XPOST 'localhost:9200/test_manual/_bulk' -H 'Content-Type: application/x-ndjson' -d '
{"index":{}}
{"id":1,"tags":["red","blue"]}
{"index":{}}
{"id":2,"tags":["green"]}
{"index":{}}
{"id":3,"tags":["red","blue"]}
'

# Test GROUP BY
curl -XPOST 'localhost:9200/_plugins/_ppl' -H 'Content-Type: application/json' -d '
{
  "query": "source=test_manual | stats count() by tags"
}'

# Expected: 2 groups ([red,blue]: 2, [green]: 1)
# Actual (if bug): 3+ groups (red, blue, green as separate)
```

### Step 4: Run General Property Tests

Now test broader correctness with random data:

```bash
# Run all property tests (not just bug-specific)
python main.py --all --iterations 50 --seed 42
```

**Example Output:**
```
2026-04-27 15:35:00 [INFO] Generating 5 test contexts...
2026-04-27 15:35:02 [INFO] Running all property tests (50 iterations)...
2026-04-27 15:37:45 [INFO] ============================================================
2026-04-27 15:37:45 [INFO] Results: 242/250 passed
2026-04-27 15:37:45 [ERROR] Found 8 failures:
2026-04-27 15:37:45 [ERROR]   TernaryLogicPartitioning violated: TLP partition count mismatch: 95 != 100
  Query: Predicate: field_2 > 50 AND field_3 = 'blue'
  Expected: 100
  Actual: 95
2026-04-27 15:37:45 [ERROR]   SortingInvariant violated: Sort order violated in ascending sort
  Query: source=test_index_2 | sort +field_1 | fields field_1
  Expected: Ascending order
  Actual: 42 > 48 at position 7
...
```

**Interpretation:**
- 242/250 passed (96.8% success rate)
- TLP failure: NULL handling issue in compound predicates
- Sorting failure: Possible type coercion issue

### Step 5: Reproduce and Report

Pick one failure and create minimal reproducing case:

```python
# Save as reproduce_tlp_failure.py
from opensearchpy import OpenSearch

client = OpenSearch(['localhost:9200'])

# Reproduce TLP failure from logs
predicate = "field_2 > 50 AND field_3 = 'blue'"

# Create test index
client.indices.create(index='repro', body={
    'mappings': {
        'properties': {
            'field_2': {'type': 'integer'},
            'field_3': {'type': 'keyword'}
        }
    }
})

# Insert 100 docs with specific distribution
# ... (minimal data that triggers bug)

# Test queries
q_true = f"source=repro | where {predicate} | stats count()"
q_false = f"source=repro | where NOT ({predicate}) | stats count()"
q_null = f"source=repro | where isnull({predicate}) | stats count()"
q_total = f"source=repro | stats count()"

# Execute and verify
# ... (should show count_true + count_false + count_null != count_total)
```

### Step 6: File GitHub Issue

**Title:** [BUG] TLP violation: Compound predicate with NULL values

**Body:**
```markdown
## Bug Description
Compound predicates incorrectly handle NULL values, violating Ternary Logic Partitioning.

## How to Reproduce
Found via automated property testing with seed 42.

[Include reproduce_tlp_failure.py]

## Expected Behavior
For predicate `field_2 > 50 AND field_3 = 'blue'`:
- Partitions (TRUE, FALSE, NULL) should cover all rows
- count_true + count_false + count_null = total_count

## Actual Behavior
- Total count: 100
- Partition sum: 95
- Missing 5 rows

## Environment
- OpenSearch version: main branch (2026-04-27)
- SQL plugin: main branch
- Found by: PPL Correctness Testing Framework

## Related Issues
Similar to #XXXX but with compound predicates
```

### Step 7: Regression Test

Once bug is fixed, add it as a regression test:

```python
# Add to properties/tlp.py
from hypothesis import example

@given(predicate=predicates(context))
@example("field_2 > 50 AND field_3 = 'blue'")  # Regression from Issue #XXXX
def test_tlp(predicate):
    # Existing TLP test...
```

Now future runs will always test this specific case.

## Advanced: Finding New Bug Patterns

### Use Hypothesis for Shrinking

```bash
# Install Hypothesis
pip install hypothesis

# Run Hypothesis-powered tests
python -c "from ppl_correctness.properties.hypothesis_tlp import HypothesisTLP; ..."
```

When Hypothesis finds a complex failure like:
```
(field_7 > 83 AND field_2 = 'purple') OR (field_5 < -42 AND field_9 != 'yellow')
```

It automatically shrinks to:
```
field_0 > 0
```

Much easier to debug!

### Combine Properties

Test property interactions:

```python
# Test: TLP + Aggregation
# If TLP partitions correctly, aggregation should conserve counts
for predicate in generate_predicates():
    # Run TLP test
    tlp_result = test_tlp(predicate)
    
    # Run aggregation conservation on same data
    agg_result = test_aggregation_conservation()
    
    # Both should pass or both should fail
    assert tlp_result.passed == agg_result.passed
```

### Stress Testing

```bash
# High iteration count to find rare bugs
python main.py --all --iterations 10000 --seed random

# Or run overnight with different seeds
for seed in {1..100}; do
    python main.py --all --iterations 100 --seed $seed
done
```

## Tips

**False Positives:**
- Some violations might be "correct" behavior (e.g., array operations are undefined)
- Check PPL docs to verify expected semantics
- Refine property if needed

**Performance:**
- Bug-specific tests are slower (create custom indices)
- General property tests are faster (reuse contexts)
- Use `--category` to test specific areas

**Debugging:**
- Add `--verbose` flag to see all queries
- Check OpenSearch logs for errors
- Use `pdb` to step through property code

**Reporting:**
- Include seed for reproducibility
- Attach minimal reproducing case
- Link to property source code
- Specify OpenSearch version

## Success Metrics

**After 1 week:**
- Found 5+ bugs
- 3+ bugs filed on GitHub
- 90%+ property pass rate

**After 1 month:**
- 2+ bugs fixed
- Regression tests added
- New properties for found bugs

**After 3 months:**
- 50+ bugs found
- Framework integrated into OpenSearch CI
- Properties used by other developers
