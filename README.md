# PPL Correctness Testing Framework

Property-based testing framework for OpenSearch PPL (Piped Processing Language), using metamorphic relations to find correctness bugs.

## Quick Test for Known Bugs

```bash
# Test if known bugs are present in your OpenSearch instance
python test_bug_coverage.py --all

# Test specific bug categories
python test_bug_coverage.py --category arrays      # Issue #5333
python test_bug_coverage.py --category nested      # Issue #4906
python test_bug_coverage.py --category text-keyword # Issue #4463
python test_bug_coverage.py --category rename      # Issue #5150
```

See [BUG_HUNTING.md](BUG_HUNTING.md) for details on what bugs are tested.

## Architecture

```
datagen/     - Generate test indices with schema and data
generators/  - PPL query generators (context-aware)
properties/  - Test properties (TLP, sorting, aggregation invariants, bug-specific tests)
runner/      - Execution engine and result validation
regression/  - Bug-specific test cases for known issues
```

## Testing Strategies

### Ternary Logic Partitioning (TLP)
Split queries by boolean predicate `p`:
- `source=index | where p | ...`
- `source=index | where not p | ...`  
- `source=index | where isnull(p) | ...`

Union should match unfiltered query.

### Aggregation Invariants
- `stats count() by field` total = `stats count()`
- `stats sum(x)` = sum of `stats sum(x) by groupfield`
- Monotonicity: `stats max(x)` >= `stats min(x)`

### Sorting Properties
- `sort field` output must be ordered
- `sort +field | head 1` = `stats max(field)`
- `sort -field | head 1` = `stats min(field)`

### Differential Testing
Compare equivalent query forms:
- `where a=1 | where b=2` vs `where a=1 and b=2`
- `eval x=a+b | where x>5` vs `where a+b>5`
- `stats count() by a,b` vs nested grouping

### Additive Pipe Property
Tests that PPL command chains are truly additive:
- For any commands A and B: `source=index | A | B` should equal `materialize(source=index | A)` then `source=materialized | B`
- Catches bugs where:
  - Commands interact in unexpected ways
  - Field references break across command boundaries
  - Intermediate state isn't properly preserved
  - Command B depends on original index structure rather than A's output

## Usage

```bash
# Run specific property tests
python main.py --property tlp --iterations 100
python main.py --property atomic --iterations 100
python main.py --property additive-pipe --iterations 50

# Run all properties
python main.py --property all --iterations 100

# Test the additive pipe property specifically
python test_additive_pipe.py
```

## Known Bug Skipping

Centralized registry in `ppl_correctness/known_bugs.py` tracks upstream bugs and skips affected test cases:

```python
from ppl_correctness.known_bugs import should_skip, register_bug
from hypothesis import assume

# In property tests
skip_reason = should_skip(query=ppl_query, field=field_obj)
if skip_reason:
    assume(False)  # Hypothesis skips this case

# Register new bugs at runtime
register_bug('bug_name', 'Issue #1234: description', 
             lambda ctx: ctx.field.is_array)
```

Current known bugs tracked in `KNOWN_BUGS` list with issue references.

## Dependencies

- Python 3.11+
- opensearch-py
- hypothesis (property-based testing)
