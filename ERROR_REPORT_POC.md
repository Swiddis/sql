# Error Report Builder POC

This POC implements a starter version of the error report builder described in [#4919](https://github.com/opensearch-project/sql/issues/4919), focusing on:
1. Better reports for missing fields with available field suggestions
2. Cursor positions on ANTLR syntax errors

## Implementation

### Core Components

#### 1. ErrorReport Class
Location: `common/src/main/java/org/opensearch/sql/common/error/ErrorReport.java`

A RuntimeException wrapper that accumulates contextual information as errors bubble up through system layers.

**Features:**
- Wraps existing exceptions without modifying their behavior
- Builder pattern for adding context incrementally
- Supports location chain, structured context, suggestions, and error codes

**Example usage:**
```java
try {
  resolveField(fieldName);
} catch (IllegalArgumentException e) {
  throw ErrorReport.wrap(e)
    .code(ErrorCode.FIELD_NOT_FOUND)
    .location("while resolving fields in the index mapping")
    .suggestion("Did you mean: 'foobar'?")
    .context("index_pattern", "logs-*")
    .context("position", cursorPosition)
    .build();
}
```

#### 2. ErrorCode Enum
Location: `common/src/main/java/org/opensearch/sql/common/error/ErrorCode.java`

Machine-readable error codes for categorizing exceptions:
- `FIELD_NOT_FOUND` - Field not found in index mapping
- `SYNTAX_ERROR` - Query syntax error
- `AMBIGUOUS_FIELD` - Multiple fields with same name
- `SEMANTIC_ERROR` - Generic semantic validation error
- `EVALUATION_ERROR` - Expression evaluation failed
- `UNKNOWN` - Unclassified error

### Enhanced Error Handling

#### 1. Syntax Errors (ANTLR)
Location: `common/src/main/java/org/opensearch/sql/common/antlr/SyntaxAnalysisErrorListener.java`

**Enhancement:** Added structured cursor position and expected tokens

**Before:**
```json
{
  "status": 400,
  "error": {
    "type": "SyntaxCheckException",
    "reason": "Invalid Query",
    "details": "[foo] is not a valid term at this part of the query: 'source=logs-*' <-- HERE. Expected tokens: ..."
  }
}
```

**After:**
```json
{
  "status": 400,
  "error": {
    "type": "ErrorReport",
    "reason": "Invalid Query",
    "details": "[foo] is not a valid term at this part of the query: 'source=logs-*' <-- HERE. Expected tokens: ...",
    "code": "SYNTAX_ERROR",
    "location": [
      "while parsing the query"
    ],
    "context": {
      "query": "source=logs-* | fields foo",
      "position": {
        "line": 1,
        "column": 25
      },
      "offending_token": "foo"
    },
    "suggestion": "Expected tokens: PIPE, WHERE, ..."
  }
}
```

#### 2. Field Resolution Errors
Location: `core/src/main/java/org/opensearch/sql/calcite/QualifiedNameResolver.java`

**Enhancement:** Added field suggestions using Levenshtein distance algorithm

**Before:**
```json
{
  "status": 400,
  "error": {
    "type": "IllegalArgumentException",
    "reason": "Invalid Query",
    "details": "Field [fooBar] not found."
  }
}
```

**After (with similar field):**
```json
{
  "status": 400,
  "error": {
    "type": "ErrorReport",
    "reason": "Invalid Query",
    "details": "Field [fooBar] not found.",
    "code": "FIELD_NOT_FOUND",
    "location": [
      "while resolving field references"
    ],
    "context": {
      "field_name": "fooBar",
      "available_fields": ["foo_bar", "foo", "bar", "baz"]
    },
    "suggestion": "Did you mean: 'foo_bar'?"
  }
}
```

**After (no similar field):**
```json
{
  "status": 400,
  "error": {
    "type": "ErrorReport",
    "reason": "Invalid Query",
    "details": "Field [xyz] not found.",
    "code": "FIELD_NOT_FOUND",
    "location": [
      "while resolving field references"
    ],
    "context": {
      "field_name": "xyz",
      "available_fields": ["foo", "bar", "baz", "qux", "quux"]
    },
    "suggestion": "Available fields: 'foo', 'bar', 'baz', 'qux', 'quux'"
  }
}
```

### Response Formatting

#### ErrorMessage Enhancement
Location: `opensearch/src/main/java/org/opensearch/sql/opensearch/response/error/ErrorMessage.java`

Enhanced to detect `ErrorReport` exceptions and serialize their rich context into JSON format.

**Serialized fields:**
- `code` - Machine-readable error code
- `location` - Array of location descriptions (innermost to outermost)
- `context` - Object with structured context data
- `suggestion` - User-facing suggestion for fixing the error

#### REST Handler Update
Location: `plugin/src/main/java/org/opensearch/sql/plugin/rest/RestPPLQueryAction.java`

Added `ErrorReport` to the `isClientError()` method to ensure it returns HTTP 400 status.

## Benefits

1. **Better Diagnostics**: Users get specific suggestions for fixing errors
2. **Structured Context**: Context data is machine-readable for better tooling
3. **Progressive Enhancement**: Existing errors still work; only enhanced errors get rich context
4. **Backward Compatible**: Original exception messages preserved for existing error handling
5. **Cursor Information**: Syntax errors now include precise line/column positions

## Next Steps

1. Add more error codes (INDEX_NOT_FOUND, TYPE_MISMATCH, etc.)
2. Enhance more error locations (joins, aggregations, etc.)
3. Add query_id to context at the outermost REST layer
4. Consider adding index pattern information for field resolution
5. Write integration tests to verify error format in real queries
6. Add documentation for error codes and their meanings

## Testing Suggestions

### Test Missing Field Error
```bash
# Start OpenSearch with plugin
# Execute a PPL query with a typo in field name

POST _plugins/_ppl
{
  "query": "source=logs-* | fields foo_ba"
}

# Should return suggestion: "Did you mean: 'foo_bar'?"
```

### Test Syntax Error
```bash
POST _plugins/_ppl
{
  "query": "source=logs-* | fieldz foo"
}

# Should return:
# - Line and column position
# - Expected tokens
# - Full query in context
```

## Files Changed

- `common/src/main/java/org/opensearch/sql/common/error/ErrorCode.java` (new)
- `common/src/main/java/org/opensearch/sql/common/error/ErrorReport.java` (new)
- `common/src/main/java/org/opensearch/sql/common/antlr/SyntaxAnalysisErrorListener.java` (modified)
- `core/src/main/java/org/opensearch/sql/calcite/QualifiedNameResolver.java` (modified)
- `opensearch/src/main/java/org/opensearch/sql/opensearch/response/error/ErrorMessage.java` (modified)
- `plugin/src/main/java/org/opensearch/sql/plugin/rest/RestPPLQueryAction.java` (modified)
