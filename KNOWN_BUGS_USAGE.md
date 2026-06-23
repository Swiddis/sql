# Known Bugs Skip System

## Overview

Centralized registry for upstream OpenSearch PPL bugs. Test properties automatically skip cases that hit known bugs using `hypothesis.assume()`.

## Quick Start

```python
from ppl_correctness.known_bugs import should_skip
from hypothesis import assume

# In a property test
skip_reason = should_skip(query=ppl_query, field=field_obj)
if skip_reason:
    assume(False)  # Hypothesis skips this case
```

## Architecture

### Registry (`ppl_correctness/known_bugs.py`)

```python
KNOWN_BUGS = [
    KnownBug(
        name="bug_identifier",
        reason="Issue #XXXX: human-readable description",
        check=lambda ctx: <predicate>  # Returns True if bug applies
    ),
]
```

### Skip Check Function

```python
should_skip(query: Optional[str] = None, 
            field=None, 
            **kwargs) -> Optional[str]
```

Returns skip reason string if any known bug matches, else `None`.

## Current Known Bugs

### #5333: Array GROUP BY Explosion
- **Pattern**: `stats count() by <array_field>`
- **Effect**: Count explosion (sum > total)
- **Check**: `'by' in query` AND `field.is_array`

### #5150: RENAME + DEDUP Nullification  
- **Pattern**: `rename X as Y | dedup Z | fields Y`
- **Effect**: All values in Y become null
- **Check**: `'rename'` AND `'dedup'` in query

### #4463: TEXT+KEYWORD Filter Mismatch
- **Pattern**: `where isnotnull(<text_field>) | stats by <text_field>`
- **Effect**: Null group still appears despite filter
- **Check**: `'isnotnull'` in query AND field has keyword subfield

## Integration Examples

### Property Test (Hypothesis)

```python
@given(test_scenario=st.data())
def test_property(self, test_scenario):
    scenario = test_scenario.draw(scenario_strategy(self._context))
    
    # Skip known bugs
    skip_reason = should_skip(query=scenario['query'], field=scenario['field'])
    assume(not skip_reason)
    
    # Rest of test...
```

### Field Filtering

```python
# Filter out problematic fields before generating test cases
safe_fields = [f for f in context.fields if not should_skip(field=f)]
```

### Query Generation

```python
# Check after generating query
query = generator.generate_query()
if should_skip(query=query, field=field_obj):
    return  # Skip this test case
```

## Adding New Bugs

### Option 1: Runtime Registration

```python
from ppl_correctness.known_bugs import register_bug

register_bug(
    name='new_bug',
    reason='Issue #XXXX: description',
    check=lambda ctx: ctx.query and 'pattern' in ctx.query.lower()
)
```

### Option 2: Update KNOWN_BUGS List

Edit `ppl_correctness/known_bugs.py`:

```python
KNOWN_BUGS = [
    # ... existing bugs ...
    KnownBug(
        name="new_bug_identifier",
        reason="Issue #XXXX: what breaks and why",
        check=lambda ctx: (
            # Multi-line conditions
            hasattr(ctx, 'query') and
            'pattern' in ctx.query.lower() and
            hasattr(ctx, 'field') and
            some_field_check(ctx.field)
        )
    ),
]
```

## Context Object

The `check` lambda receives a context object with:
- `ctx.query`: PPL query string (if provided)
- `ctx.field`: Field object (if provided)
- `ctx.<kwarg>`: Any additional kwargs passed to `should_skip()`

```python
# Example: check multiple attributes
check=lambda ctx: (
    hasattr(ctx, 'query') and 'GROUP BY' in ctx.query and
    hasattr(ctx, 'field') and ctx.field.is_array
)
```

## Benefits

1. **Centralized**: One source of truth for known bugs
2. **Traceable**: Each bug links to GitHub issue
3. **Automatic**: Properties skip without manual filtering
4. **Discoverable**: New bugs detected when they don't match patterns
5. **Removable**: Delete bug from list when upstream fixes land

## Maintenance

When upstream bug is fixed:
1. Remove entry from `KNOWN_BUGS` list
2. Rerun test suite
3. Verify bug no longer occurs
4. Update `ISSUE_TRACKER.md`
5. Remove any manual workarounds in property code

## Testing the Registry

```bash
uv run python -c "
from ppl_correctness.known_bugs import should_skip
from ppl_correctness.datagen.context import Field, FieldType

# Test known pattern
query = 'source=x | rename a as b | dedup c | fields b'
print(should_skip(query=query))  # Should match #5150

# Test safe pattern  
query = 'source=x | stats count()'
print(should_skip(query=query))  # Should be None
"
```
