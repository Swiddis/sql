# Error Cases for Enhancement

This document tracks different error scenarios encountered during testing, categorized by whether they currently use the enhanced ErrorReport system.

## ✅ Currently Enhanced (POC Complete)

### 1. ANTLR Syntax Errors
**Test Query:**
```sql
source=big5 | fieldz message
```

**Current Output:**
```json
{
  "error": {
    "type": "ErrorReport",
    "code": "SYNTAX_ERROR",
    "details": "[fieldz] is not a valid term...",
    "location": ["while parsing the query"],
    "context": {
      "query": "source=big5 | fieldz message",
      "position": {"line": 1, "column": 14},
      "offending_token": "fieldz"
    },
    "suggestion": "Expected tokens: 'WHERE', 'FIELDS', 'TABLE'..."
  }
}
```

**Status:** ✅ Working perfectly with cursor position and suggestions

---

### 2. PPL Field Not Found (Calcite Path)
**Test Query:**
```sql
source=big5 | fields messag
```

**Current Output:**
```json
{
  "error": {
    "type": "ErrorReport",
    "code": "FIELD_NOT_FOUND",
    "details": "Field [messag] not found.",
    "location": ["while resolving field references"],
    "context": {
      "field_name": "messag",
      "available_fields": ["agent", "agent.ephemeral_id", ...]
    },
    "suggestion": "Did you mean: 'message'?"
  }
}
```

**Status:** ✅ Working with Levenshtein distance suggestions

---

## ❌ Not Enhanced Yet (Candidates for Expansion)

### 3. SQL Field Not Found
**Test Query:**
```sql
SELECT messag FROM big5 LIMIT 1
```

**Current Output:**
```json
{
  "error": {
    "type": "SemanticCheckException",
    "reason": "Invalid SQL query",
    "details": "can't resolve Symbol(namespace=FIELD_NAME, name=messag) in type env"
  }
}
```

**Why Not Enhanced:**
- SQL analyzer throws `SemanticCheckException` before field resolution
- Goes through different code path than PPL Calcite path
- Doesn't use `QualifiedNameResolver`

**Enhancement Opportunity:**
- Add similar field suggestion logic to SQL analyzer
- Extract field suggestion logic into a shared utility class
- Could provide even better context: which table/index the symbol lookup failed in

**Location:** Likely in `/workplace/sawiddis/sql/sql/src/main/java/org/opensearch/sql/sql/domain/SQLQueryRequest.java` or SQL analyzer

---

### 4. Index/Pattern Not Found
**Test Query:**
```sql
source=nonexistent_index | fields message
```

**Expected Current Output:**
```json
{
  "error": {
    "type": "IndexNotFoundException",
    "reason": "Invalid Query",
    "details": "no such index [nonexistent_index]"
  }
}
```

**Enhancement Opportunity:**
- Add code: `INDEX_NOT_FOUND`
- Suggest similar index names (using Levenshtein distance on available indices)
- Show available indices/patterns that match prefix
- Context: index pattern used, available indices

**Example Enhanced Output:**
```json
{
  "error": {
    "type": "ErrorReport",
    "code": "INDEX_NOT_FOUND",
    "details": "Index [nonexistent_index] not found.",
    "location": ["while resolving index pattern"],
    "context": {
      "index_pattern": "nonexistent_index",
      "available_indices": ["big5", "logs-2024", "metrics-*", ...]
    },
    "suggestion": "Did you mean: 'nonexistent-index'? Or try pattern: 'nonexistent*'"
  }
}
```

**Location:** Need to enhance where `IndexNotFoundException` is caught/thrown

---

### 5. Type Mismatch Errors
**Test Query:**
```sql
source=big5 | where @timestamp > "not-a-date"
source=big5 | where message > 123
```

**Expected Current Output:**
```json
{
  "error": {
    "type": "SemanticCheckException",
    "details": "Type mismatch..."
  }
}
```

**Enhancement Opportunity:**
- Add code: `TYPE_MISMATCH`
- Show expected type vs provided type
- Suggest correct format (e.g., for dates: "Expected ISO 8601 format: '2024-01-01T00:00:00Z'")
- Context: field name, field type, provided value

**Example Enhanced Output:**
```json
{
  "error": {
    "type": "ErrorReport",
    "code": "TYPE_MISMATCH",
    "details": "Cannot compare DATE field with STRING value",
    "location": ["while type checking expression"],
    "context": {
      "field": "@timestamp",
      "expected_type": "DATE",
      "actual_type": "STRING",
      "provided_value": "not-a-date"
    },
    "suggestion": "Use ISO 8601 date format: '2024-01-01T00:00:00Z'"
  }
}
```

---

### 6. Ambiguous Field (Join Context)
**Test Query:**
```sql
source=index1 | join left=l right=r ON l.id = r.id | fields name
```
(when both indices have a `name` field)

**Expected Current Output:**
```json
{
  "error": {
    "type": "IllegalArgumentException",
    "details": "Ambiguous field: name"
  }
}
```

**Enhancement Opportunity:**
- Add code: `AMBIGUOUS_FIELD`
- Show which tables/aliases contain the field
- Suggest qualified names

**Example Enhanced Output:**
```json
{
  "error": {
    "type": "ErrorReport",
    "code": "AMBIGUOUS_FIELD",
    "details": "Field [name] is ambiguous",
    "location": ["while resolving field references in join"],
    "context": {
      "field": "name",
      "found_in": ["l", "r"]
    },
    "suggestion": "Use qualified name: 'l.name' or 'r.name'"
  }
}
```

**Location:** Already in `QualifiedNameResolver.java` line ~201, just needs ErrorReport wrapping

---

### 7. Function Not Found
**Test Query:**
```sql
source=big5 | eval result = unknownfunc(message)
```

**Expected Current Output:**
```json
{
  "error": {
    "type": "SemanticCheckException",
    "details": "Unknown function..."
  }
}
```

**Enhancement Opportunity:**
- Add code: `FUNCTION_NOT_FOUND`
- Suggest similar function names
- Show function signature hints

**Example Enhanced Output:**
```json
{
  "error": {
    "type": "ErrorReport",
    "code": "FUNCTION_NOT_FOUND",
    "details": "Function [unknownfunc] not found",
    "location": ["while resolving function call"],
    "context": {
      "function_name": "unknownfunc",
      "available_functions": ["upper", "lower", "concat", ...]
    },
    "suggestion": "Did you mean: 'unknown_func'? Or try: 'concat', 'substr'"
  }
}
```

---

### 8. Invalid Function Arguments
**Test Query:**
```sql
source=big5 | eval result = substring(message)  -- missing required args
source=big5 | eval result = abs("not-a-number")
```

**Expected Current Output:**
```json
{
  "error": {
    "type": "SemanticCheckException",
    "details": "Invalid function call..."
  }
}
```

**Enhancement Opportunity:**
- Add code: `INVALID_FUNCTION_ARGS`
- Show function signature
- Explain what's wrong (too few args, wrong type, etc.)

**Example Enhanced Output:**
```json
{
  "error": {
    "type": "ErrorReport",
    "code": "INVALID_FUNCTION_ARGS",
    "details": "Function [substring] requires 2-3 arguments, got 1",
    "location": ["while validating function call"],
    "context": {
      "function_name": "substring",
      "expected_signature": "substring(string, start, [length])",
      "provided_args": ["message"]
    },
    "suggestion": "Usage: substring(message, 0, 10)"
  }
}
```

---

### 9. Aggregation Errors
**Test Query:**
```sql
source=big5 | stats count() by invalid_field
source=big5 | eval x = message | stats avg(x) by message  -- can't aggregate string
```

**Enhancement Opportunity:**
- Add code: `AGGREGATION_ERROR`
- Show which field/expression can't be aggregated and why
- Suggest valid numeric fields for numeric aggregations

---

### 10. Runtime Execution Errors (Shard Failures)
**Test Query:**
```sql
source=big5 | where host.nam = "test"  -- non-existent nested field in WHERE
```

**Current Output:**
```json
{
  "error": {
    "type": "SearchPhaseExecutionException",
    "reason": "Error occurred in OpenSearch engine: all shards failed",
    "details": "Shard[0]: ... QueryShardException[failed to create query ..."
  }
}
```

**Why This Happens:**
- PPL allows selecting non-existent fields (returns null)
- But using them in operations (WHERE, aggregations) fails at execution time
- Error occurs at OpenSearch shard level, not in SQL plugin

**Enhancement Opportunity:**
- Wrap `SearchPhaseExecutionException` with ErrorReport
- Extract meaningful details from nested exceptions
- Provide better context about what query operation failed

**Example Enhanced Output:**
```json
{
  "error": {
    "type": "ErrorReport",
    "code": "EXECUTION_ERROR",
    "details": "Query execution failed at shard level",
    "location": ["while executing query on OpenSearch"],
    "context": {
      "query": "source=big5 | where host.nam = \"test\"",
      "shard_failures": 1,
      "failed_operation": "filter condition evaluation"
    },
    "suggestion": "Check that field 'host.nam' exists. Available nested fields: 'host.name'"
  }
}
```

---

## Demo Test Plan

For a comprehensive demo, run these queries in sequence:

### Basic Syntax Errors
1. `source=big5 | fieldz message` - typo in command
2. `source=big5 | fields message where x=1` - wrong order
3. `source=big5 | fields message |` - incomplete query

### Field Resolution Errors
4. `source=big5 | fields messag` - field typo (1 char off)
5. `source=big5 | fields msg` - field abbreviation
6. `source=big5 | fields xyz123` - completely wrong field
7. `source=big5 | fields host.nam` - nested field typo

### Index Errors
8. `source=big6 | fields message` - index typo
9. `source=nonexistent | fields *` - completely wrong index

### SQL Path
10. `SELECT messag FROM big5` - field error in SQL
11. `SELECT * FROM big6` - index error in SQL

### Complex Cases
12. `source=big5 | where message > 123` - type mismatch
13. `source=big5 | eval x = unknownfunc(message)` - function not found
14. `source=big5 | stats count() by xyz` - aggregation on missing field

### Show Success Cases Too
15. `source=big5 | fields message | head 5` - working query
16. `source=big5 | where message = "test"` - working filter

---

## Implementation Priority

**High Priority (Demo Blockers):**
1. SQL field not found - very common use case
2. Index not found - common mistake
3. Ambiguous field in joins - easy win, already has detection

**Medium Priority (Nice to Have):**
4. Type mismatch errors - good UX improvement
5. Function not found - helpful for new users
6. Aggregation errors - clarifies complex operations

**Low Priority (Future Work):**
7. Invalid function arguments - requires signature metadata
8. Runtime execution errors - complex to extract meaningful info from shard failures

---

## Code Locations for Enhancement

1. **SQL Field Resolution**:
   - `/workplace/sawiddis/sql/sql/src/main/java/org/opensearch/sql/sql/domain/SQLQueryRequest.java`
   - SQL semantic analyzer (need to find exact location)

2. **Index Resolution**:
   - Wherever `IndexNotFoundException` is thrown/caught
   - Likely in datasource/index resolution layer

3. **Ambiguous Fields**:
   - Already detected in `QualifiedNameResolver.java:201`
   - Just needs ErrorReport wrapping (easy fix)

4. **Type Mismatches**:
   - Semantic analyzer type checking
   - Expression evaluator

5. **Function Resolution**:
   - Function registry lookup
   - Expression builder

6. **Shared Utilities Needed**:
   - Extract Levenshtein distance to utility class
   - Field suggestion helper (can be reused across SQL/PPL/functions/indices)
