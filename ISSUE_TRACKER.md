# Ad-Hoc Issue Tracker

Leverage this for issue tracking pertaining to bugs found by this particular script.
As this is a side project that actually does find some good stuff, we want to keep an eye on what regressions or nontrivial workarounds we find, so over time we can remove them.
This is a local suppliment to the actual github issue tracking. Prefer to always update this with new issues.

## Known Bugs Registry (ppl_correctness/known_bugs.py)

All known upstream bugs are now tracked in `KNOWN_BUGS` list with automatic skip logic.
Use `should_skip(query, field, ...)` to check if test case hits a known bug.
Properties integrate via `hypothesis.assume()` to skip affected cases.

### Issue #5333: Array GROUP BY Explosion
**Status**: Known upstream bug, auto-skipped  
**Pattern**: `GROUP BY` on array fields  
**Effect**: Count explodes - each array element creates separate group  
**Skip Logic**: Detects `'by' in query` + `field.is_array`  
**Remove when**: Issue #5333 fixed

### Issue #5150: RENAME + DEDUP Nullification
**Status**: Known upstream bug, auto-skipped  
**Pattern**: Field renamed then passed through `dedup`  
**Effect**: Renamed field values become null  
**Skip Logic**: Detects `'rename'` + `'dedup'` in query  
**Remove when**: Issue #5150 fixed

### Issue #4463: TEXT+KEYWORD Filter Mismatch
**Status**: Known upstream bug, auto-skipped  
**Pattern**: `isnotnull()` filter on TEXT field with keyword subfield  
**Effect**: Filter doesn't apply - null group still appears in aggregation  
**Skip Logic**: Detects `'isnotnull'` in query + `field.subfields['keyword']`  
**Remove when**: Issue #4463 fixed

### Array Field Generation Disabled
**Status**: Preventive workaround for #5333  
**Workaround**: Array generation commented out in `context.py` line ~212  
**Remove when**: Issue #5333 fixed and verified stable

## Active Workarounds

### Struct Type Materialization (additive_pipe.py)
**Status**: Not a bug - feature complexity  
**Implementation**: Nested/properties inference via `infer_properties()` function (lines ~235-265)  
**Note**: Uses recursive type inference from sample data. Handles both nested objects and arrays of objects.

## Adding New Known Bugs

```python
from ppl_correctness.known_bugs import register_bug

register_bug(
    name='bug_name',
    reason='Issue #XXXX: description',
    check=lambda ctx: <predicate that matches bug pattern>
)
```

Or add directly to `KNOWN_BUGS` list in `known_bugs.py`.
