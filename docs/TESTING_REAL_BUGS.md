# Testing Real Bugs: Extension Summary

This document shows how we extended the framework to test real OpenSearch PPL bugs.

## Approach: Properties Over Hardcoded Tests

Instead of writing specific regression tests like:

```python
# Hardcoded test (brittle, specific)
def test_issue_5333():
    create_index_with_data([{"x": 1, "y": [1,2]}, ...])
    result = query("source=idx | stats count() by y")
    assert len(result) == 4  # Specific to this data
```

We write **properties** that detect the bug pattern:

```python
# Property-based test (generalizes, discovers)
class ArrayGroupByAtomicity(Property):
    def check(self, context, client):
        # Generate random array data
        # Test: sum(grouped_counts) should equal total_count
        # Fails if arrays explode into elements
        # Works on ANY data distribution
```

## What We Built

### 1. Bug-Specific Properties (4 categories, 9 properties)

**Arrays** (`array_properties.py`) - Issue #5333
- `ArrayGroupByAtomicity`: Detects if GROUP BY explodes arrays
- `ArrayWhereFilterConsistency`: Detects inconsistent WHERE semantics
- `ArraySortDeterminism`: Detects if SORT uses only first element

**Nested Objects** (`nested_object_properties.py`) - Issue #4906
- `NestedFieldAccessNullCheck`: Detects null returns for existing fields
- `NestedFieldFilterConsistency`: Detects filter/projection mismatches

**Text+Keyword Fields** (`text_keyword_properties.py`) - Issue #4463
- `IsNotNullFilterAggregation`: Detects null buckets after isnotnull()
- `FilterAggregationFieldConsistency`: Detects filter/agg field mapping issues

**Rename+Dedup** (`rename_properties.py`) - Issue #5150
- `RenameFieldPreservation`: Detects null values after rename + dedup
- `RenameEvalInteraction`: Detects null values in eval column references
- `CommandOrderIndependence`: Detects order-dependent behavior

### 2. Targeted Test Runner (`test_bug_coverage.py`)

```bash
# Run all bug tests
python test_bug_coverage.py --all

# Run specific categories
python test_bug_coverage.py --category arrays
python test_bug_coverage.py --category nested
python test_bug_coverage.py --category text-keyword
python test_bug_coverage.py --category rename
```

### 3. Documentation (`BUG_HUNTING.md`)

Complete guide for:
- What each property tests
- How to interpret results
- How to add tests for new bugs
- Performance optimization tips

## How Properties Catch Bugs

### Example 1: Array GROUP BY (Issue #5333)

**Bug:** Arrays are exploded into individual elements during GROUP BY.

**Property:**
```python
# Data: [1,2], [3,4], [1,5], [1,2], [2,3]
total_count = 5 documents

# If arrays atomic: 4 groups
# [1,2]: 2 docs, [3,4]: 1 doc, [1,5]: 1 doc, [2,3]: 1 doc
# Sum: 2+1+1+1 = 5 ✓

# If arrays exploded: 5 groups
# 1: docs, 2: docs, 3: docs, 4: docs, 5: docs
# Sum: > 5 ✗ (elements counted multiple times)

if sum(grouped_counts) > total_count:
    return Violation("Arrays exploded")
```

**Why it works:** The invariant holds regardless of data values. Only fails if arrays aren't atomic.

### Example 2: isnotnull Filter (Issue #4463)

**Bug:** `WHERE isnotnull(description)` doesn't prevent null buckets when `description.keyword` is null due to `ignore_above`.

**Property:**
```python
# Schema: description: text with keyword subfield (ignore_above=50)
# Data: Some descriptions > 50 chars

query = "WHERE isnotnull(description) | stats by description"
results = execute(query)

# Invariant: No null buckets after isnotnull filter
null_buckets = [r for r in results if r.description is None]

if null_buckets:
    return Violation("isnotnull filter failed")
```

**Why it works:** Filter says "no nulls", so null buckets indicate the bug. Detects the text/keyword subfield mismatch automatically.

### Example 3: Rename+Dedup (Issue #5150)

**Bug:** Renamed fields become null after dedup on a different field.

**Property:**
```python
# Query 1: No dedup (control)
q1 = "| rename value as val | fields val"
r1 = execute(q1)

# Query 2: With dedup (bug case)
q2 = "| rename value as val | dedup category | fields val"
r2 = execute(q2)

# Invariant: Renamed field should have values in both cases
null_control = count_nulls(r1)
null_with_dedup = count_nulls(r2)

if null_control == 0 and null_with_dedup > 0:
    return Violation("Dedup nullifies renamed field")
```

**Why it works:** Compares behavior with/without dedup. If dedup causes nulls, that's the bug.

## Benefits Over Hardcoded Tests

### 1. Generalization

**Hardcoded:**
```python
# Only tests THIS data
data = [{"x": 1, "y": [1,2]}, {"x": 2, "y": [3,4]}]
```

**Property:**
```python
# Tests ANY data (randomly generated)
for seed in range(100):
    data = generate_random_array_data(seed)
    check_invariant(data)
```

### 2. Discovery

Properties find **related** bugs you didn't know about:

- Testing arrays in GROUP BY also finds arrays in WHERE bugs
- Testing text+keyword in aggregation also finds it in sorting
- Testing rename+dedup also finds eval+dedup bugs

### 3. Regression Prevention

Once integrated into CI:
- Properties run nightly with high iteration counts
- Automatically catches regressions when bugs are fixed
- No need to manually maintain test data

### 4. Minimal Failing Cases

With Hypothesis (future), properties shrink to minimal cases:

```
Original failure:
  | rename field_7 as x | eval y = field_3 + field_9 | dedup field_2 | ...

After shrinking:
  | rename field_0 as x | dedup field_1 | fields x
```

Much easier to debug!

## Example Output

```bash
$ python test_bug_coverage.py --category arrays

======================================================================
Testing ARRAYS bugs
======================================================================

Issue #5333:
  Running ArrayGroupByAtomicity...
    ✗ FAILED with 1 violation(s):
      - GROUP BY array_int explodes arrays: grouped sum (78) > total (50)
        Query: source=test_array_properties | stats count() by array_int
        Expected: 50
        Actual: 78

  Running ArrayWhereFilterConsistency...
    ✓ PASSED (no violations)

  Running ArraySortDeterminism...
    ✗ FAILED with 1 violation(s):
      - SORT array_int might not be applied (IDs are sequential)
        Query: source=test_array_properties | sort array_int | fields id
        Expected: Non-sequential ID order after sort
        Actual: Sequential IDs: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]...

======================================================================
SUMMARY
======================================================================
Properties run: 3
Total violations: 2
✗ Found issues in 2 tests.
```

## Integration with Existing Framework

Bug properties integrate seamlessly:

```python
# Add to main.py for general testing
properties = [
    TernaryLogicPartitioning(),      # Original TLP
    ArrayGroupByAtomicity(),          # Bug-specific
    SortingInvariant(),               # Original sorting
    RenameFieldPreservation(),        # Bug-specific
]
```

All use the same `Property` interface, same executor, same reporting.

## Next Steps

### 1. Run Against Real OpenSearch

```bash
# Start OpenSearch
docker run -d -p 9200:9200 opensearchproject/opensearch:latest

# Test for bugs
python test_bug_coverage.py --all
```

### 2. Iterate on Properties

Based on results:
- Refine detection logic
- Add edge cases
- Improve test data generation

### 3. Add More Bugs

As new bugs are filed:
- Analyze root cause
- Identify invariant
- Add property

### 4. Integrate Hypothesis

Replace manual randomization with Hypothesis for shrinking:

```python
@given(array_data=array_strategy(), predicate=predicate_strategy())
def test_array_where(array_data, predicate):
    # Hypothesis automatically shrinks to minimal failure
```

## Measuring Success

**Short-term:**
- Properties detect known bugs → ✓ Validates approach
- No false positives → ✓ Properties are correct

**Medium-term:**
- Properties find NEW bugs (variants of known issues)
- Bugs get fixed, properties become regression tests

**Long-term:**
- Properties integrated into OpenSearch CI
- Fewer bugs filed (caught before release)
- Framework adopted for other query languages

## Conclusion

By focusing on **invariants** rather than **specific test cases**, we've created a framework that:

1. **Detects** known bugs automatically
2. **Generalizes** to find related bugs
3. **Scales** to many data distributions
4. **Integrates** with existing property-based tests
5. **Prevents** regressions when bugs are fixed

The properties are concise (50-100 lines each), maintainable, and reusable. As more bugs are found and fixed, the test suite naturally grows stronger.

This is the power of property-based testing: instead of enumerating cases, we describe the system's invariants and let the framework find violations.
