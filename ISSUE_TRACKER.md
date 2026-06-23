# Ad-Hoc Issue Tracker

Leverage this for issue tracking pertaining to bugs found by this particular script.
As this is a side project that actually does find some good stuff, we want to keep an eye on what regressions or nontrivial workarounds we find, so over time we can remove them.
This is a local suppliment to the actual github issue tracking. Prefer to always update this with new issues.

## Array Field Issues - GENERATION DISABLED

### Array Generation Disabled (context.py:212-215)
**Status**: Multiple known issues with array fields  
**Workaround**: Array generation commented out entirely  
**Code**: `context.py` line ~212  
**Remove when**: All array-related issues resolved (see below)  
**Note**: Field filtering workarounds in properties can be removed once generation is re-enabled

### Array GROUP BY Explosion (#5333)
**Status**: Known upstream bug  
**Workaround**: Generation disabled + filter array fields from test cases  
**Code**: `additive_pipe.py` line ~93: `if not f.is_array`  
**Remove when**: Issue #5333 is fixed  
**Details**: `GROUP BY` on array fields explodes count - each array element creates a separate group, violating conservation property

## Active Workarounds in additive_pipe.py

### Struct Type Materialization
**Status**: Not a bug - feature complexity  
**Workaround**: Now supported via nested/properties inference  
**Code**: Lines ~235-265: `infer_properties()` function  
**Note**: Uses recursive type inference from sample data. Handles both nested objects and arrays of objects.

