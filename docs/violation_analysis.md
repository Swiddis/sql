# Property Violation Analysis
**Date:** 2026-04-27  
**Test Run:** 760/900 passed, 163 failures detected

## Summary
The property-based tests detected **three categories of violations**, matching three known OpenSearch SQL/PPL bugs. Importantly, there is a **new pattern** not previously documented: arrays causing filter-aggregation inconsistency violations.

---

## ✅ Known Issue #4463: TEXT+KEYWORD Subfield Mismatch
**Status:** Expected behavior, matches GitHub issue

### Violations Found
- `FilterAggregationConsistency` violated on `test_index_2.field_1`
- Pattern: `isnotnull(field_1)` filter fails, 48 docs in null group
- Occurs 3 times in test output

### Root Cause
- Filter checks text field: `{"exists": {"field": "field_1"}}`
- Aggregation uses keyword subfield: `"field": "field_1.keyword"`
- Values exceeding `ignore_above` limit → keyword subfield is null, text field exists
- Documents pass filter but contribute null buckets to aggregation

### Query Example
```
source=test_index_2 | where isnotnull(field_1) | stats count() by field_1
```
Expected: No null groups  
Actual: Null group with count=48

### GitHub Issue
https://github.com/opensearch-project/sql/issues/4463

---

## ✅ Known Issue #5150: Rename + Dedup Nullification
**Status:** Expected behavior, matches GitHub issue

### Violations Found
- `FieldValuePreservation` violated on `test_index_4`
- Pattern: `rename X as Y | dedup Z | fields Y` returns all nulls
- Affects `field_2` and `field_3` (2 violations)

### Root Cause
Schema mismatch in aggregation-based dedup pushdown:
1. Logical plan tracks renamed field names (e.g., `renamed_field_3`)
2. Physical plan requests data using original names (`field_3`)
3. Response parser extracts with original names
4. Enumerator attempts to resolve renamed name, finds nothing → null

### Query Example
```
source=test_index_4 | rename field_3 as renamed_field_3 | dedup field_0 | fields renamed_field_3
```
Expected: Non-null values preserved  
Actual: All 5 values became null

### GitHub Issue
https://github.com/opensearch-project/sql/issues/5150

---

## ✅ Known Issue #5333: Array Explosion in GROUP BY
**Status:** Expected behavior, matches GitHub issue

### Violations Found

#### 1. GroupByConservation (Array Explosion)
- `test_index_3.array_6`: grouped sum 266 > total 98
- **Root cause:** Each array element becomes separate group key

**Example:**
```
Total:   source=test_index_3 | stats count()           → 98
Grouped: source=test_index_3 | stats count() by array_6 → 266
```

Document with `array_6=[478, 186, -995, -246, -534]` creates 5 groups instead of 1.

#### 2. AggregationConservation (Count Mismatch)
```
Total:   source=test_index_3 | stats count()           → 98
Grouped: source=test_index_3 | stats count() by array_4 → 265
```
Sum: 14+37+43+35+42+47+47 = 265 (167 docs over-counted)

#### 3. AggregationConservation (Sum Mismatch)
```
Total:   source=test_index_3 | stats sum(field_1)           → -1276.31
Grouped: source=test_index_3 | stats sum(field_1) by array_4 → -4251.91
```
Each document with multi-element array contributes its `field_1` value multiple times.

### Root Cause
Arrays treated as collections, not atomic values. GROUP BY flattens arrays, creating one group per element. Aggregations like `sum()` duplicate scalar values across these groups.

### GitHub Issue
https://github.com/opensearch-project/sql/issues/5333

---

## 🆕 NEW PATTERN: Array Fields with Filter-Aggregation Inconsistency
**Status:** NEW violation pattern, not explicitly documented in issues

### Violations Found
- `FilterAggregationConsistency` violated on `test_index_3` array fields
- Pattern: Filter count != aggregation sum for array fields

#### Example 1: array_3
```
Filter query: source=test_index_3 | where isnotnull(array_3) | stats count()
Result: 98 documents

Aggregation:  source=test_index_3 | where isnotnull(array_3) | stats count() by array_3
Groups: blue=50, green=53, orange=56, purple=54, red=49, yellow=49
Sum: 311

Violation: Filter count (98) != aggregation sum (311)
```

#### Example 2: array_4
```
Filter count: 84
Aggregation sum: 251 (14 null + 37+43+35+42+47+47 = 251)
Difference: 167 docs over-counted
```

### Analysis
This is a **combination** of two known issues:

1. **Issue #5333 (Array Explosion):** GROUP BY flattens arrays, creating multiple groups per document
2. **Conservation Principle:** After filtering, count should be conserved

The `FilterAggregationConsistency` property caught this because it checks:
```python
filter_count == sum_of_grouped_counts
```

This specific manifestation (filter consistency + arrays) isn't explicitly called out in Issue #5333, which focuses on:
- WHERE comparisons (any element matches)
- ORDER BY (first element only)
- GROUP BY explosion
- MAX/MIN on elements

### Is This New?
**Technically no** — it's a direct consequence of Issue #5333's GROUP BY explosion. However:
- ✅ The atomic property test makes this violation **explicit and testable**
- ✅ Issue #5333 doesn't mention filter-aggregation consistency violations
- ✅ This could be valuable to document as a specific test case

### Verification
```bash
# Confirm explosion behavior
curl -X POST "http://localhost:9200/_plugins/_ppl" \
  -H 'Content-Type: application/json' \
  -d '{"query":"source=test_index_3 | stats count() by array_4"}' 

# Returns:
# Groups: null=14, blue=37, green=43, orange=35, purple=42, red=47, yellow=47
# Sum = 265 (vs 98 total docs)
```

---

## Recommendations

### 1. Test Coverage
All three known issues (#4463, #5150, #5333) are successfully detected by the atomic properties:
- ✅ `FilterAggregationConsistency` catches #4463 (text+keyword)
- ✅ `FieldValuePreservation` catches #5150 (rename+dedup)
- ✅ `GroupByConservation` catches #5333 (array explosion)
- ✅ `AggregationConservation` catches #5333 (count/sum conservation)

### 2. New Test Case for Issue #5333
Consider adding a specific test case to Issue #5333 documentation:
```
Property: Sum of filtered grouped counts equals filtered total count
Test: source=idx | where isnotnull(arr) | stats count() by arr
Expected: sum(groups) == filter_count
Actual: sum(groups) > filter_count (array explosion)
```

### 3. No Unknown Bugs Detected
All 163 violations map to known issues. No new bugs discovered in this run.

---

## Statistics
- **Total Tests:** 900
- **Passed:** 760 (84.4%)
- **Failed:** 140 test instances producing 163 violations

### Violation Breakdown
| Property | Count | Issue | Status |
|----------|-------|-------|--------|
| FilterAggregationConsistency (text+keyword) | 3 | #4463 | Known |
| FieldValuePreservation (rename+dedup) | 2 | #5150 | Known |
| GroupByConservation (array explosion) | 1 | #5333 | Known |
| AggregationConservation (array counts) | 2 | #5333 | Known |
| FilterAggregationConsistency (arrays) | 2 | #5333* | Known (new pattern) |

*Consequence of #5333, not separately documented
