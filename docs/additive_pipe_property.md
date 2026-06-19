# Additive Pipe Property

## Overview

The Additive Pipe Property tests a fundamental invariant of PPL (Piped Processing Language): command chains should be truly compositional. That is, for any two command sequences A and B:

```
source=index | A | B
```

should produce identical results to:

```
1. materialize(source=index | A) → temp_index
2. source=temp_index | B
```

## Motivation

PPL is designed as a pipeline language where commands are chained together with the pipe (`|`) operator. Each command should transform its input and pass the result to the next command. This property verifies that:

1. Commands truly operate on their input (not the original index)
2. Intermediate state is correctly preserved across command boundaries
3. Field references work correctly after transformations
4. No hidden dependencies exist on original index structure

## Implementation

The property is implemented in `ppl_correctness/properties/additive_pipe.py` as the `AdditivePipeProperty` class.

### Test Strategy

For each test iteration:

1. Generate two compatible PPL commands A and B
2. Execute the direct pipeline: `source=index | A | B`
3. Execute command A and materialize results to a temporary index
4. Execute command B on the materialized index
5. Compare the results

### Test Cases

The property tests several command combinations:

1. **Project → Filter**: `fields f1, f2 | where isnotnull(f2)`
   - Tests that field projection followed by filtering works correctly

2. **Filter & Project → Aggregate**: `where isnotnull(f1) | fields f2 | stats count() by f2`
   - Tests that filtered and projected data can be aggregated

3. **Aggregate → Limit**: `stats count() by field | head 5`
   - Tests that aggregation results can be further processed

### Materialization Process

When materializing intermediate results:

1. Execute command A and capture both data and schema
2. Infer OpenSearch field types from:
   - PPL schema information
   - Actual data values (for accuracy)
3. Create temporary index with appropriate mappings
4. Handle special cases:
   - Nested field paths (e.g., `obj.nested.value`)
   - Aggregation result columns (e.g., `count()`)
   - Type conversions (integer strings → long fields)
5. Ensure field order is preserved (important for result comparison)

### Key Implementation Details

**Type Inference**: PPL may return type information that doesn't perfectly match what's needed for re-indexing. The implementation:
- Examines actual data values in addition to schema
- Detects numeric strings and indexes them as numbers
- Handles aggregation result types (count, sum, avg, etc.)

**Field Name Sanitization**: Aggregation column names like `count()` are sanitized to valid OpenSearch field names while preserving order.

**Field Order Preservation**: When querying the materialized index, fields are explicitly projected in the original order using a `fields` command to ensure result structure matches.

**Error Handling**: If more than 50% of documents fail to index (due to type mismatches or schema issues), the test is skipped rather than reported as a failure.

## Bugs This Property Can Catch

1. **Command interaction bugs**: Where command B behaves differently depending on whether it's chained with A or runs independently

2. **Field reference bugs**: Where field names or references break across command boundaries

3. **State preservation bugs**: Where intermediate state (computed fields, filters, etc.) isn't properly passed to subsequent commands

4. **Type handling bugs**: Where data types change unexpectedly through command chains

5. **Ordering bugs**: Where command order affects results when it shouldn't

## Usage

```bash
# Run just the additive pipe property
python main.py --property additive-pipe --iterations 50

# Run as part of all properties
python main.py --property all --iterations 100

# Standalone test script
python test_additive_pipe.py
```

## Limitations

Currently, the property focuses on simple field types and avoids:
- Complex nested structures
- Array fields (which can behave unexpectedly in PPL)
- Commands that produce non-deterministic results
- Commands with complex syntax that may not compose well

These limitations exist to avoid false positives from known PPL quirks and to focus on core compositional properties.

## Future Enhancements

Potential improvements:

1. Test more command combinations (EVAL, RENAME, DEDUP, SORT)
2. Handle array fields and nested objects more robustly
3. Test longer command chains (A | B | C)
4. Add support for commands with parameters (SPAN, RARE, etc.)
5. Verify that non-deterministic commands (like RARE) are skipped appropriately

## References

- Base property interface: `ppl_correctness/properties/base.py`
- Test execution framework: `ppl_correctness/runner/executor.py`
- Index generation: `ppl_correctness/datagen/context.py`
