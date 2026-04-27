## Bug Hunting Guide

This framework now includes targeted tests for known OpenSearch PPL bugs. Here's how to use it to find and verify bugs.

## Quick Start

```bash
# Test for all known bug categories
python test_bug_coverage.py --all

# Test specific categories
python test_bug_coverage.py --category arrays
python test_bug_coverage.py --category nested
python test_bug_coverage.py --category text-keyword
python test_bug_coverage.py --category rename
```

## Bug Coverage

### Issue #5333: Array Atomicity
**Status:** Open  
**Properties:**
- `ArrayGroupByAtomicity`: Detects if GROUP BY explodes arrays into elements
- `ArrayWhereFilterConsistency`: Detects if WHERE uses "any element" semantics
- `ArraySortDeterminism`: Detects if SORT only uses first element

**What to look for:**
- GROUP BY count sum > total documents (arrays exploded)
- WHERE matches unexpectedly high percentage of docs
- SORT order is non-deterministic or ignores array tails

**Test files:** `ppl_correctness/properties/array_properties.py`

### Issue #4906: Nested Object Access Returns Null
**Status:** Open  
**Properties:**
- `NestedFieldAccessNullCheck`: Detects null values for existing nested fields
- `NestedFieldFilterConsistency`: Detects filter/projection mismatches

**What to look for:**
- Fields like `nested_obj.c.d` return null despite existing
- WHERE nested.field = X returns docs where nested.field is null

**Test files:** `ppl_correctness/properties/nested_object_properties.py`

### Issue #4463: isnotnull() Filter Not Applied
**Status:** Open  
**Properties:**
- `IsNotNullFilterAggregation`: Detects null buckets after isnotnull() filter
- `FilterAggregationFieldConsistency`: Detects field mapping mismatches

**What to look for:**
- `WHERE isnotnull(field) | stats by field` contains null buckets
- Text field exists but keyword subfield is null (ignore_above)
- Filter count != aggregation sum

**Test files:** `ppl_correctness/properties/text_keyword_properties.py`

### Issue #5150: Dedup Nullifies Renamed Fields
**Status:** Open  
**Properties:**
- `RenameFieldPreservation`: Detects null values after rename + dedup
- `RenameEvalInteraction`: Detects null values in eval'd column references
- `CommandOrderIndependence`: Detects order-dependent nullification

**What to look for:**
- `| rename field as alias | dedup other | fields alias` returns nulls
- `| eval x = field | dedup other | fields x` returns nulls
- Different command orders produce different null patterns

**Test files:** `ppl_correctness/properties/rename_properties.py`

## How Properties Find Bugs

### Property-Based Approach

Instead of hardcoding specific test cases, properties define **invariants** that should always hold:

**Example: Array GROUP BY**
```python
# Invariant: Sum of grouped counts = total count
total = execute("source=idx | stats count()")
grouped = execute("source=idx | stats count() by array_field")

assert sum(grouped) == total  # Fails if arrays exploded
```

**Why this works:**
- If arrays are atomic: Each doc in exactly one group → sum = total
- If arrays exploded: Each element becomes a group → sum > total
- Automatically detects the bug without knowing exact data

### Coverage Through Randomization

Properties test across:
- Different data distributions (random seed)
- Different field types (int, keyword, nested)
- Different value ranges
- Different array sizes

This finds edge cases that manual tests miss.

## Extending for New Bugs

When you find a new bug:

### Step 1: Identify the Invariant

What property should hold but doesn't?

**Examples:**
- Arrays: GROUP BY should not explode arrays
- Nested: Field access should return values that exist
- Text+keyword: Filter and aggregation should use same field
- Rename: Field values should survive command chains

### Step 2: Create a Property Class

```python
class MyBugProperty(Property):
    @property
    def name(self) -> str:
        return "MyBugName_IssueXXXX"

    def check(self, context, client) -> List[PropertyViolation]:
        # 1. Create test index with bug-triggering schema
        # 2. Execute queries that should expose bug
        # 3. Check if invariant holds
        # 4. Return violations if bug detected
```

### Step 3: Add to test_bug_coverage.py

```python
from ppl_correctness.properties.my_properties import MyBugProperty

all_properties = {
    'my-category': [
        ('Issue #XXXX', [
            MyBugProperty(),
        ]),
    ],
}
```

## Interpreting Results

### Violation Found = Likely Bug

```
✗ FAILED with 1 violation(s):
  - GROUP BY array_int explodes arrays: grouped sum (75) > total (50)
    Expected: 50
    Actual: 75
```

**What this means:** Arrays are being exploded into elements in GROUP BY.  
**Action:** File/update GitHub issue with minimal reproducing case.

### No Violations = Bug Fixed or Not Triggered

```
✓ PASSED (no violations)
```

**Possible reasons:**
1. Bug is actually fixed
2. Test data didn't trigger the bug condition
3. Property doesn't cover this specific case

**Action:** Run with different seeds, verify fix manually, or refine property.

### Error = Infrastructure Issue

```
✗ ERROR: Connection refused
```

**Action:** Check OpenSearch is running, credentials correct, etc.

## Finding NEW Bugs

The existing properties can find variants of known bugs:

### Combine Commands

Test interactions between commands:
- RENAME + EVAL + WHERE
- DEDUP + SORT + HEAD
- WHERE + STATS + FIELDS

Many bugs occur at command boundaries.

### Vary Field Types

Test same operations on:
- Arrays vs scalars
- Text vs keyword vs text+keyword
- Nested vs flat
- Timestamps vs integers

Type handling is error-prone.

### Use Hypothesis Strategies

Once Hypothesis is integrated, you can generate:
- Arbitrary command chains
- Arbitrary predicates
- Arbitrary field combinations

This explores the space automatically.

## Regression Testing

When a bug is fixed:

1. **Keep the property:** Tests prevent regressions
2. **Add to CI:** Run nightly to catch breakage
3. **Document fix:** Note which OpenSearch version fixed it
4. **Add `@example` decorator** (with Hypothesis):

```python
from hypothesis import example

@given(predicate=predicates(context))
@example("field_0 > 5")  # Specific case that used to fail
def test_array_where(predicate):
    ...
```

## Performance Tips

Bug-finding properties can be slow (they create indices, insert data, execute queries).

**Optimize:**
- Use `--category` to run subset
- Reduce doc counts in context generators
- Run in parallel (future)
- Cache index creation across properties

**Trade-off:** Faster tests vs more thorough coverage.

## Contributing Bug Tests

When you find a bug in the wild:

1. **Reproduce minimally:** Find smallest query + data that triggers it
2. **Identify the invariant:** What property is violated?
3. **Generalize:** What pattern of queries would catch this?
4. **Implement property:** Create test that detects the pattern
5. **Verify:** Confirm property fails on buggy version, passes on fix
6. **Submit:** PR with property + documentation

Your property might find related bugs you didn't know existed!

## Resources

- [Issue #5333 - Arrays](https://github.com/opensearch-project/sql/issues/5333)
- [Issue #4906 - Nested Objects](https://github.com/opensearch-project/sql/issues/4906)
- [Issue #4463 - isnotnull Filter](https://github.com/opensearch-project/sql/issues/4463)
- [Issue #5150 - Rename+Dedup](https://github.com/opensearch-project/sql/issues/5150)
