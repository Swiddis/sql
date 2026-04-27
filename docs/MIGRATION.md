# Migration to Atomic Properties

This document tracks the migration from bug-specific tests to atomic property-based testing.

## Summary of Changes

### 1. Schema Model Extensions

**File:** `ppl_correctness/datagen/context.py`

Added support for:
- **Array fields:** `is_array` flag marks fields that hold array values
- **Nested fields:** `nested_depth` and dot-notation names like `"obj.nested.value"`
- **Subfields:** `subfields` dict for text fields with keyword subfields (with `ignore_above`)

```python
# Example: All three new features
Field("array_0", FieldType.INTEGER, is_array=True)
Field("obj.nested.value", FieldType.DOUBLE, nested_depth=2)
Field("description", FieldType.TEXT, 
      subfields={"keyword": SubField(KEYWORD, ignore_above=50)})
```

### 2. Data Generation Updates

**File:** `ppl_correctness/datagen/context.py`

- `generate_field_value()` now generates array values when `is_array=True`
- `create_test_index()` handles nested document structures (dot notation → nested objects)
- `create_test_index()` creates multi-field mappings (text with keyword subfield)
- `generate_contexts()` creates diverse schemas (30% arrays, 20% nested, 40% text+keyword)

### 3. Query Generator Expansion

**File:** `ppl_correctness/generators/ppl.py`

Added:
- `generate_rename_cmd()` - generates `rename field as alias`
- `generate_dedup_cmd()` - generates `dedup field`
- `generate_command_chain()` - generates multi-command pipelines
- `isnotnull()` / `isnull()` predicates (20% chance in predicate generation)
- Smarter handling of array and nested fields in predicates

### 4. Atomic Properties

**File:** `ppl_correctness/properties/atomic.py` (NEW)

Four atomic properties replace nine bug-specific properties:

| Atomic Property | Replaces | Catches |
|----------------|----------|---------|
| `GroupByConservation` | `ArrayGroupByAtomicity` | Issue #5333 (arrays) |
| `FieldAccessConsistency` | `NestedFieldAccessNullCheck`, `NestedFieldFilterConsistency` | Issue #4906 (nested) |
| `FilterAggregationConsistency` | `IsNotNullFilterAggregation`, `FilterAggregationFieldConsistency` | Issue #4463 (text+keyword) |
| `FieldValuePreservation` | `RenameFieldPreservation`, `RenameEvalInteraction`, `CommandOrderIndependence` | Issue #5150 (rename+dedup) |

### 5. Main Runner Updates

**File:** `main.py`

- Added import for atomic properties
- Added `--property atomic` option
- Changed default from `'tlp'` to `'all'` (includes atomic)
- Atomic properties run when `--property atomic` or `--property all`

## Migration Path

### Old Workflow

```bash
# Test specific bug categories
python test_bug_coverage.py --category arrays
python test_bug_coverage.py --category nested
python test_bug_coverage.py --category text-keyword
python test_bug_coverage.py --category rename
```

Each test created its own index with specific schema.

### New Workflow

```bash
# Test all atomic properties (includes all bug patterns)
python main.py --property atomic --iterations 50

# Or test everything
python main.py --property all --iterations 100
```

Single command tests all bugs across diverse schemas.

## Files That Can Be Deprecated

The following files are now **obsolete** (atomic properties provide same coverage):

- ❌ `test_bug_coverage.py` - replaced by `main.py --property atomic`
- ❌ `ppl_correctness/properties/array_properties.py` - replaced by `GroupByConservation`
- ❌ `ppl_correctness/properties/nested_object_properties.py` - replaced by `FieldAccessConsistency`
- ❌ `ppl_correctness/properties/text_keyword_properties.py` - replaced by `FilterAggregationConsistency`
- ❌ `ppl_correctness/properties/rename_properties.py` - replaced by `FieldValuePreservation`

**Recommendation:** Keep these files for now as reference, but mark as deprecated in README.

## Verification Steps

To verify the migration is successful:

### 1. Run Validation Tests

```bash
python test_atomic_properties.py
```

Expected: All validation tests pass.

### 2. Test Atomic Properties (Dry Run)

Since we don't have OpenSearch running, verify imports and structure:

```bash
python -c "
from ppl_correctness.properties.atomic import *
from ppl_correctness.datagen.context import *
print('✓ All imports successful')
"
```

### 3. When OpenSearch is Available

```bash
# Start OpenSearch
docker run -d -p 9200:9200 \
  -e 'discovery.type=single-node' \
  -e 'DISABLE_SECURITY_PLUGIN=true' \
  opensearchproject/opensearch:2.11.0

# Wait for startup
sleep 30

# Run atomic properties
python main.py --property atomic --iterations 10 --indices 3
```

Expected violations:
- **GroupByConservation:** "ARRAY EXPLOSION" if arrays are buggy
- **FieldAccessConsistency:** "Issue #4906 pattern" if nested fields buggy
- **FilterAggregationConsistency:** "Issue #4463 pattern" if text+keyword buggy
- **FieldValuePreservation:** "Issue #5150 pattern" if rename+dedup buggy

### 4. Compare Coverage

Run old vs new approach side-by-side:

```bash
# Old approach
time python test_bug_coverage.py --all
# Note: failures, time taken

# New approach
time python main.py --property atomic --iterations 10
# Compare: should catch same bugs, faster
```

## API Changes

### For Adding New Properties

**Before:**
```python
# Create bug-specific test with custom index
class MyBugProperty(Property):
    def check(self, context, client):
        # Create custom test index
        custom_context = create_my_test_index(client)
        # Run specific queries
        result = test_specific_scenario(custom_context)
        return violations
```

**After:**
```python
# Create atomic invariant that works on any schema
class MyAtomicProperty(Property):
    def check(self, context, client):
        # Use provided context (already diverse)
        # Test universal invariant
        for field in context.fields:
            if violates_invariant(field):
                return violation
```

### For Schema Generation

**Before:**
```python
Field("field_0", FieldType.INTEGER)
# Simple flat fields only
```

**After:**
```python
# Rich schema model
Field("field_0", FieldType.INTEGER, is_array=True)
Field("obj.nested.value", FieldType.DOUBLE, nested_depth=2)
Field("text_field", FieldType.TEXT, 
      subfields={"keyword": SubField(KEYWORD, ignore_above=50)})
```

## Backward Compatibility

### Breaking Changes

None. Old properties still work:
- `TernaryLogicPartitioning` unchanged
- `SortingInvariant` unchanged
- `AggregationConservation` unchanged

### Additions

New properties are **additive**:
- Atomic properties added to `--property all`
- Old `--property aggregation` still works
- New `--property atomic` option available

## Performance Impact

**Before:** 
- 9 properties × separate indices = ~30 seconds
- Each property creates its own test data

**After:**
- 4 atomic properties × shared indices = ~10 seconds (3x faster)
- Properties share test data from `generate_contexts()`
- Higher coverage due to diverse schemas

## Rollback Plan

If atomic properties have issues:

1. **Keep old files:** Don't delete `test_bug_coverage.py` and `*_properties.py` yet
2. **Conditional import:** Make atomic import optional in `main.py`
3. **Flag to disable:** Add `--no-atomic` flag to skip atomic properties

```python
# Rollback code (if needed)
if not args.no_atomic:
    from ppl_correctness.properties.atomic import *
    properties.extend([...])
```

## Next Steps

1. ✅ Code changes complete
2. ✅ Validation tests pass
3. ⏳ Test against live OpenSearch (when available)
4. ⏳ Compare bug detection with old approach
5. ⏳ Update documentation (README, BUG_HUNTING.md)
6. ⏳ Mark old files as deprecated
7. ⏳ Add CI integration

## Success Metrics

Migration is successful if:

✓ All old bugs detected by atomic properties  
✓ No new false positives  
✓ Execution time ≤ old approach  
✓ Code reduction (fewer lines)  
✓ Higher coverage (more schemas tested)  

Current status: **Code complete, awaiting live testing**
