# Error Report Enhancement Demo

This document contains example queries and their enhanced error outputs for demonstrating the error report builder improvements.

## Index Used for Demo
- **Index name**: `big5`
- **Sample fields**: `message`, `host.name`, `agent.id`, `@timestamp`, `event.dataset`, etc.

---

## 1. Syntax Errors with Cursor Position

### Example 1.1: Typo in PPL Command

**Query:**
```
source=big5 | fieldz message
```

**Error Response:**
```json
{
  "status": 400,
  "error": {
    "type": "ErrorReport",
    "code": "SYNTAX_ERROR",
    "reason": "Invalid Query",
    "details": "[fieldz] is not a valid term at this part of the query: 'source=big5 | fieldz' <-- HERE. Expecting one of 48 possible tokens. Some examples: 'WHERE', 'FIELDS', 'TABLE', 'RENAME', 'STATS', ...",
    "location": [
      "while parsing the query"
    ],
    "context": {
      "query": "source=big5 | fieldz message",
      "position": {
        "line": 1,
        "column": 14
      },
      "offending_token": "fieldz"
    },
    "suggestion": "Expected one of 48 possible tokens. Examples: 'WHERE', 'FIELDS', 'TABLE', 'RENAME', 'STATS'"
  }
}
```

**Key Features:**
- ✅ Exact cursor position (line 1, column 14)
- ✅ Offending token identified
- ✅ List of valid alternatives

### Example 1.2: SQL "IS NOT NULL" Syntax

**Query:**
```
source=big5 | where message is not null
```

**Error Response:**
```json
{
  "status": 400,
  "error": {
    "type": "ErrorReport",
    "code": "SYNTAX_ERROR",
    "reason": "Invalid Query",
    "details": "[is] is not a valid term at this part of the query: '...ig5 | where message is' <-- HERE. Expecting one of 24 possible tokens. Some examples: EOF, 'IN', 'NOT', 'OR', 'AND', ...",
    "location": [
      "while parsing the query"
    ],
    "context": {
      "query": "source=big5 | where message is not null",
      "position": {
        "line": 1,
        "column": 28
      },
      "offending_token": "is"
    },
    "suggestion": "PPL doesn't support 'IS NOT NULL' syntax. Use isnotnull(message) function instead."
  }
}
```

**Key Features:**
- ✅ Detects SQL syntax pattern
- ✅ Suggests PPL function alternative
- ✅ Includes actual field name in suggestion

### Example 1.3: SQL "IS NULL" Syntax

**Query:**
```
source=big5 | where host.name is null
```

**Error Response:**
```json
{
  "status": 400,
  "error": {
    "type": "ErrorReport",
    "code": "SYNTAX_ERROR",
    "reason": "Invalid Query",
    "details": "[is] is not a valid term at this part of the query: '...| where host.name is' <-- HERE. Expecting one of 24 possible tokens. Some examples: EOF, 'IN', 'NOT', 'OR', 'AND', ...",
    "location": [
      "while parsing the query"
    ],
    "context": {
      "query": "source=big5 | where host.name is null",
      "position": {
        "line": 1,
        "column": 30
      },
      "offending_token": "is"
    },
    "suggestion": "PPL doesn't support 'IS NULL' syntax. Use isnull(host.name) function instead."
  }
}
```

**Key Features:**
- ✅ Works with nested fields (host.name)
- ✅ Pattern-specific suggestion

---

## 2. Field Resolution Errors with Smart Suggestions

### Example 2.1: Simple Field Typo (1 character off)

**Query:**
```
source=big5 | fields messag
```

**Error Response:**
```json
{
  "status": 400,
  "error": {
    "type": "ErrorReport",
    "code": "FIELD_NOT_FOUND",
    "reason": "Invalid Query",
    "details": "Field [messag] not found.",
    "location": [
      "while resolving field references"
    ],
    "context": {
      "field_name": "messag",
      "position": {
        "line": 1,
        "column": 21
      },
      "available_fields": ["agent", "agent.ephemeral_id", "agent.id", "...40 more fields"]
    },
    "suggestion": "Did you mean: 'message'?"
  }
}
```

**Key Features:**
- ✅ Exact position where field appears (line 1, column 21)
- ✅ Levenshtein distance matching
- ✅ Suggests most similar field
- ✅ Shows all available fields in context

### Example 2.2: Completely Wrong Field Name

**Query:**
```
source=big5 | fields xyz123
```

**Error Response:**
```json
{
  "status": 400,
  "error": {
    "type": "ErrorReport",
    "code": "FIELD_NOT_FOUND",
    "reason": "Invalid Query",
    "details": "Field [xyz123] not found.",
    "location": [
      "while resolving field references"
    ],
    "context": {
      "field_name": "xyz123",
      "position": {
        "line": 1,
        "column": 21
      },
      "available_fields": ["agent", "agent.ephemeral_id", "agent.id", "agent.name", "agent.type", "..."]
    },
    "suggestion": "Available fields: 'agent', 'agent.ephemeral_id', 'agent.id', 'agent.name', 'agent.type', ..."
  }
}
```

**Key Features:**
- ✅ Exact position where field appears
- ✅ When no similar match, shows first 5 available fields
- ✅ Helps users discover what fields exist

### Example 2.3: Nested Field Typo

**Query:**
```
source=big5 | fields host.nam
```

**Error Response:**
```json
{
  "status": 400,
  "error": {
    "type": "ErrorReport",
    "code": "FIELD_NOT_FOUND",
    "reason": "Invalid Query",
    "details": "Field [host.nam] not found.",
    "location": [
      "while resolving field references"
    ],
    "context": {
      "field_name": "host.nam",
      "position": {
        "line": 1,
        "column": 21
      },
      "available_fields": ["agent", "host", "host.name", "..."]
    },
    "suggestion": "Did you mean: 'host.name'?"
  }
}
```

**Key Features:**
- ✅ Exact position of nested field reference
- ✅ Works with dotted field names
- ✅ Suggests correct nested field

---

## 3. Context-Aware Field Errors

### Example 3.1: Field Removed by `fields` Command

**Query:**
```
source=big5 | fields message | where host.name = "test"
```

**Error Response:**
```json
{
  "status": 400,
  "error": {
    "type": "ErrorReport",
    "code": "FIELD_NOT_FOUND",
    "reason": "Invalid Query",
    "details": "Field [host.name] not found.",
    "location": [
      "while resolving field references"
    ],
    "context": {
      "field_name": "host.name",
      "position": {
        "line": 1,
        "column": 37
      },
      "fields_command_used": true,
      "available_fields": ["message"]
    },
    "suggestion": "Field [host.name] not in current context. Note: A 'fields' command earlier in the query removed fields not explicitly listed. Current fields: 'message'"
  }
}
```

**Key Features:**
- ✅ Exact position where field is referenced in WHERE clause
- ✅ Detects `fields` command was used
- ✅ Explains why field is not available
- ✅ Shows current available fields after projection

### Example 3.2: Multiple Fields Selected, One Missing

**Query:**
```
source=big5 | fields message, host.name, @timestamp | where agent.id = "xyz"
```

**Error Response:**
```json
{
  "status": 400,
  "error": {
    "type": "ErrorReport",
    "code": "FIELD_NOT_FOUND",
    "reason": "Invalid Query",
    "details": "Field [agent.id] not found.",
    "location": [
      "while resolving field references"
    ],
    "context": {
      "field_name": "agent.id",
      "position": {
        "line": 1,
        "column": 59
      },
      "fields_command_used": true,
      "available_fields": ["message", "host.name", "@timestamp"]
    },
    "suggestion": "Field [agent.id] not in current context. Note: A 'fields' command earlier in the query removed fields not explicitly listed. Current fields: 'message', 'host.name', '@timestamp'"
  }
}
```

**Key Features:**
- ✅ Exact position of field reference in WHERE clause
- ✅ Shows all currently available fields after projection
- ✅ Makes it clear which fields user can access

---

## 4. Comparison with Old Error Format

### Old Format (Before Enhancement)

**Query:** `source=big5 | fields messag`

**Old Error:**
```json
{
  "status": 400,
  "error": {
    "type": "IllegalArgumentException",
    "reason": "Invalid Query",
    "details": "Field [messag] not found."
  }
}
```

**Problems:**
- ❌ No suggestion for similar field
- ❌ No list of available fields
- ❌ No machine-readable error code
- ❌ No structured context

### New Format (After Enhancement)

**Query:** `source=big5 | fields messag`

**New Error:**
```json
{
  "status": 400,
  "error": {
    "type": "ErrorReport",
    "code": "FIELD_NOT_FOUND",
    "reason": "Invalid Query",
    "details": "Field [messag] not found.",
    "location": ["while resolving field references"],
    "context": {
      "field_name": "messag",
      "position": {
        "line": 1,
        "column": 21
      },
      "available_fields": ["message", "host", "agent", "..."]
    },
    "suggestion": "Did you mean: 'message'?"
  }
}
```

**Improvements:**
- ✅ **Exact position** where error occurs (line/column)
- ✅ Smart suggestion using fuzzy matching
- ✅ Machine-readable error code
- ✅ Structured context with available fields
- ✅ Location chain for debugging

**Visual Display Possibility:**
```
source=big5 | fields messag
                     ^^^^^^
                     Error: Field [messag] not found.
                     Did you mean: 'message'?
```

---

## 5. Demo Script for Testing

### Setup
```bash
# Ensure OpenSearch is running with big5 index
curl -X GET http://localhost:9200/big5/_count

# Should return count > 0
```

### Test Commands

```bash
# Test 1: Syntax error with cursor position
curl -X POST http://localhost:9200/_plugins/_ppl \
  -H 'Content-Type: application/json' \
  -d '{"query": "source=big5 | fieldz message"}' | jq '.'

# Test 2: IS NOT NULL pattern
curl -X POST http://localhost:9200/_plugins/_ppl \
  -H 'Content-Type: application/json' \
  -d '{"query": "source=big5 | where message is not null"}' | jq '.'

# Test 3: IS NULL pattern
curl -X POST http://localhost:9200/_plugins/_ppl \
  -H 'Content-Type: application/json' \
  -d '{"query": "source=big5 | where host.name is null"}' | jq '.'

# Test 4: Field typo (1 char off)
curl -X POST http://localhost:9200/_plugins/_ppl \
  -H 'Content-Type: application/json' \
  -d '{"query": "source=big5 | fields messag"}' | jq '.'

# Test 5: Completely wrong field
curl -X POST http://localhost:9200/_plugins/_ppl \
  -H 'Content-Type: application/json' \
  -d '{"query": "source=big5 | fields xyz123"}' | jq '.'

# Test 6: Field after projection
curl -X POST http://localhost:9200/_plugins/_ppl \
  -H 'Content-Type: application/json' \
  -d '{"query": "source=big5 | fields message | where host.name = \"test\""}' | jq '.'

# Test 7: Multiple fields projection
curl -X POST http://localhost:9200/_plugins/_ppl \
  -H 'Content-Type: application/json' \
  -d '{"query": "source=big5 | fields message, host.name, @timestamp | where agent.id = \"xyz\""}' | jq '.'
```

### Pretty Print Error (Example)

```bash
# Extract just the key information
curl -X POST http://localhost:9200/_plugins/_ppl \
  -H 'Content-Type: application/json' \
  -d '{"query": "source=big5 | fields messag"}' | \
  jq '.error | {code, details, suggestion, context: {field_name, available_fields: .available_fields[:5]}}'
```

---

## 6. Key Metrics for Demo

### Coverage
- ✅ Syntax errors: cursor position + expected tokens
- ✅ Field errors: **exact position** + smart suggestions
- ✅ SQL pattern detection: IS [NOT] NULL → function suggestions
- ✅ Field typos: Levenshtein distance suggestions
- ✅ Context awareness: fields command detection
- ✅ Machine-readable: error codes for programmatic handling

### User Experience Improvements
1. **Pinpoint accuracy**: Every error includes exact line/column position
2. **Visual debugging**: Display scripts can point directly at errors with carets (^)
3. **Better guidance**: Actionable suggestions instead of generic errors
4. **Learning aid**: Helps SQL users learn PPL syntax
5. **Context awareness**: Explains why fields are not available
6. **Tooling support**: Structured errors enable IDE integrations and linters

---

## 7. Future Enhancements (Not in POC)

Based on ERROR_CASES_TO_ENHANCE.md, these are next priorities:

1. **Index not found**: Suggest similar index names
2. **SQL field resolution**: Apply same suggestions to SQL queries
3. **Ambiguous field in joins**: Suggest qualified names
4. **Type mismatch errors**: Show expected vs actual types
5. **Function not found**: Suggest similar function names

See ERROR_SUGGESTION_ARCHITECTURE.md for scaling to many patterns.
