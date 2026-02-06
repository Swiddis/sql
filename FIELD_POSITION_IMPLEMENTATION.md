# Adding Field Position Information to Error Reports

## Current Problem

When field resolution fails, we know **which** field is missing but not **where** it appears in the query. This makes it harder for users to spot the error, especially in complex queries.

## Why We Don't Have Positions Now

The flow is:
1. **ANTLR Parsing** → ParseTree with token positions ✅ HAS POSITIONS
2. **AstBuilder** → Converts ParseTree to AST nodes ❌ LOSES POSITIONS
3. **Calcite Planning** → Uses AST nodes for field resolution ❌ NO POSITIONS
4. **Error Reporting** → Can't report position ❌ NO POSITIONS

The issue is at step 2: `AstExpressionBuilder.visitIdentifiers()` (line 830) creates `QualifiedName` objects from `ParserRuleContext` but doesn't preserve position information:

```java
// Current code - LOSES position info
public QualifiedName visitIdentifiers(List<? extends ParserRuleContext> ctx) {
  return new QualifiedName(
      ctx.stream()
          .map(RuleContext::getText)  // ❌ Only extracts text
          .map(StringUtils::unquoteIdentifier)
          .collect(Collectors.toList()));
}
```

But `ParserRuleContext` HAS positions available:
```java
ctx.get(0).getStart().getLine();       // ✅ Line number
ctx.get(0).getStart().getCharPositionInLine();  // ✅ Column number
```

## Solution: Add Position Info to AST Nodes

### Option 1: Add Position to Node Base Class (Recommended)

**Pros:**
- All AST nodes get positions automatically
- Useful for many error types, not just field resolution
- Follows ANTLR best practices

**Cons:**
- Larger change across codebase
- Need to update all AstBuilder methods

**Implementation:**

```java
// 1. Update Node base class
public abstract class Node {
  // New fields
  @Getter private final int line;
  @Getter private final int column;

  // Default constructor for backward compatibility
  public Node() {
    this(-1, -1);
  }

  // Constructor with position
  public Node(int line, int column) {
    this.line = line;
    this.column = column;
  }

  // Helper to check if position is available
  public boolean hasPosition() {
    return line >= 0 && column >= 0;
  }
}
```

```java
// 2. Update QualifiedName to pass positions
@EqualsAndHashCode(callSuper = false)
public class QualifiedName extends UnresolvedExpression {
  private final List<String> parts;

  // Existing constructors
  public QualifiedName(String name) {
    super();  // No position
    this.parts = Collections.singletonList(name);
  }

  // New constructor with position
  public QualifiedName(List<String> parts, int line, int column) {
    super(line, column);
    this.parts = parts;
  }
}
```

```java
// 3. Update AstExpressionBuilder to capture positions
public QualifiedName visitIdentifiers(List<? extends ParserRuleContext> ctx) {
  if (ctx.isEmpty()) {
    return new QualifiedName(Collections.emptyList());
  }

  // Extract position from first token
  Token startToken = ctx.get(0).getStart();
  int line = startToken.getLine();
  int column = startToken.getCharPositionInLine();

  // Extract field parts
  List<String> parts = ctx.stream()
      .map(RuleContext::getText)
      .map(StringUtils::unquoteIdentifier)
      .collect(Collectors.toList());

  return new QualifiedName(parts, line, column);
}
```

```java
// 4. Update QualifiedNameResolver to use position
private static RuntimeException getNotFoundException(
    QualifiedName node, CalcitePlanContext context) {
  String fieldName = node.toString();

  // ... existing code ...

  // Add position to error report if available
  if (node.hasPosition()) {
    Map<String, Object> position = new HashMap<>();
    position.put("line", node.getLine());
    position.put("column", node.getColumn());
    reportBuilder.context("position", position);
  }

  // ... rest of error building ...
}
```

### Option 2: Add Position Only to QualifiedName (Faster)

**Pros:**
- Minimal changes
- Gets us field positions quickly
- No changes to Node base class

**Cons:**
- Only helps with field errors
- Each expression type needs separate handling
- Doesn't scale to other error types

**Implementation:**

```java
// 1. Add fields directly to QualifiedName
public class QualifiedName extends UnresolvedExpression {
  private final List<String> parts;
  private final int line;
  private final int column;

  // Existing constructors
  public QualifiedName(String name) {
    this.parts = Collections.singletonList(name);
    this.line = -1;
    this.column = -1;
  }

  // New constructor with position
  public QualifiedName(List<String> parts, int line, int column) {
    this.parts = parts;
    this.line = line;
    this.column = column;
  }

  public boolean hasPosition() {
    return line >= 0 && column >= 0;
  }

  public int getLine() { return line; }
  public int getColumn() { return column; }
}
```

Then update AstExpressionBuilder and QualifiedNameResolver as in Option 1.

### Option 3: Pass Position Through Context (Hack)

**Pros:**
- No AST changes needed
- Quick and dirty

**Cons:**
- Brittle and hard to maintain
- Doesn't work across all code paths
- Not recommended for production

## Recommended Approach

**Go with Option 1** (add to Node base class) because:

1. **Scales to other errors**: Can use positions for function calls, operators, etc.
2. **Standard practice**: Most parsers store positions in AST nodes
3. **Future-proof**: Makes implementing other position-based features easier
4. **Clean design**: Position is a fundamental property of any parsed node

## Implementation Plan

### Phase 1: Add Position Infrastructure
1. Add `line` and `column` fields to `Node` base class
2. Add constructor that accepts positions
3. Keep existing no-arg constructor for backward compatibility

### Phase 2: Update AST Builders
1. Modify `AstExpressionBuilder.visitIdentifiers()` to capture positions
2. Update other builder methods as needed (fields, functions, etc.)
3. Test that positions are correctly captured

### Phase 3: Use Positions in Error Reports
1. Update `QualifiedNameResolver.getNotFoundException()` to include position
2. Update error message formatter to display position
3. Test with various error scenarios

### Phase 4: Extend to Other Errors
1. Add positions to function call errors
2. Add positions to operator errors
3. Add positions to type mismatch errors

## Testing

```java
@Test
public void testFieldPositionInError() {
  String query = "source=big5 | fields message | where badfield = 'test'";

  try {
    execute(query);
    fail("Should have thrown field not found error");
  } catch (ErrorReport e) {
    assertEquals("FIELD_NOT_FOUND", e.getCode());

    Map<String, Object> position = e.getContext().get("position");
    assertEquals(1, position.get("line"));
    assertEquals(40, position.get("column"));  // Position of "badfield"
  }
}
```

## Example Output

**Query:**
```
source=big5 | fields message | where badField = "test"
```

**Error (Before):**
```json
{
  "code": "FIELD_NOT_FOUND",
  "details": "Field [badField] not found.",
  "suggestion": "Did you mean: 'message'?"
}
```

**Error (After):**
```json
{
  "code": "FIELD_NOT_FOUND",
  "details": "Field [badField] not found.",
  "context": {
    "field_name": "badField",
    "position": {
      "line": 1,
      "column": 40
    },
    "available_fields": ["message"]
  },
  "suggestion": "Did you mean: 'message'?"
}
```

**Display Script Can Show:**
```
source=big5 | fields message | where badField = "test"
                                     ^^^^^^^^
                                     Error: Field [badField] not found.
                                     Did you mean: 'message'?
```

## Effort Estimate

- **Option 1 (Recommended)**: 2-3 days
  - Day 1: Add position to Node, update builders
  - Day 2: Update error reporters, test
  - Day 3: Handle edge cases, documentation

- **Option 2 (Quick fix)**: 4-6 hours
  - Only adds position to QualifiedName
  - Good for POC, but doesn't scale

## Benefits

1. **Better UX**: Users can immediately see where the error is
2. **IDE Integration**: Editors can highlight the exact error location
3. **Faster Debugging**: No need to manually find the bad field
4. **Consistent with Syntax Errors**: Field errors now match quality of syntax errors

## Conclusion

We **absolutely can** add field positions - the information is available from ANTLR, we just need to preserve it through the AST building process. The recommended approach is to add position fields to the Node base class, which gives us positions for all AST nodes and enables many future improvements.
