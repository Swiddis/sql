# Error Suggestion Architecture Improvements

## Current Implementation Issues

The current pattern detection in `SyntaxAnalysisErrorListener.getCustomSuggestion()` hardcodes pattern matching logic directly in the error listener. This approach has several problems:

1. **Not Scalable**: Each new pattern requires modifying the error listener
2. **Hard to Test**: Pattern matching logic is tightly coupled with ANTLR token handling
3. **No Prioritization**: Can't easily prioritize which patterns to check first
4. **Difficult to Maintain**: Logic becomes complex as more patterns are added
5. **No Extensibility**: Can't add new patterns without modifying core code

## Proposed Architecture: Pattern-Based Suggestion System

### 1. Pattern Registry with Strategy Pattern

Create a registry of suggestion providers that can be applied independently:

```java
/**
 * Interface for syntax error suggestion providers.
 * Each provider checks for a specific pattern and returns a suggestion if matched.
 */
public interface SyntaxErrorSuggestionProvider {
  /**
   * Check if this provider can suggest a fix for the given error context.
   *
   * @param context Error context containing tokens, query, offending token
   * @return Optional suggestion, or empty if pattern doesn't match
   */
  Optional<String> getSuggestion(SyntaxErrorContext context);

  /**
   * Priority for this provider (lower = higher priority).
   * Providers with lower priority values are checked first.
   */
  int getPriority();
}

/**
 * Context object containing all information needed for pattern matching.
 */
public class SyntaxErrorContext {
  private final Token offendingToken;
  private final CommonTokenStream tokens;
  private final String query;
  private final RecognitionException exception;

  // Helper methods for common operations
  public String getOffendingText() { ... }
  public String getRemainingQuery() { ... }
  public Token getPreviousToken() { ... }
  public Token getNextToken() { ... }
  public List<Token> getTokensInRange(int start, int end) { ... }
}
```

### 2. Specific Pattern Providers

Each pattern gets its own class:

```java
/**
 * Detects "IS [NOT] NULL" pattern and suggests function alternative.
 */
public class IsNullSuggestionProvider implements SyntaxErrorSuggestionProvider {

  @Override
  public Optional<String> getSuggestion(SyntaxErrorContext context) {
    if (!"is".equalsIgnoreCase(context.getOffendingText())) {
      return Optional.empty();
    }

    String remaining = context.getRemainingQuery().trim().toLowerCase();

    if (remaining.startsWith("not null")) {
      String fieldName = extractFieldName(context);
      return Optional.of(String.format(
        "PPL doesn't support 'IS NOT NULL' syntax. Use isnotnull(%s) function instead.",
        fieldName));
    }

    if (remaining.startsWith("null")) {
      String fieldName = extractFieldName(context);
      return Optional.of(String.format(
        "PPL doesn't support 'IS NULL' syntax. Use isnull(%s) function instead.",
        fieldName));
    }

    return Optional.empty();
  }

  @Override
  public int getPriority() {
    return 10; // High priority for common SQL patterns
  }

  private String extractFieldName(SyntaxErrorContext context) { ... }
}

/**
 * Detects SELECT * FROM pattern in PPL context.
 */
public class SelectStarSuggestionProvider implements SyntaxErrorSuggestionProvider {

  @Override
  public Optional<String> getSuggestion(SyntaxErrorContext context) {
    if (!"select".equalsIgnoreCase(context.getOffendingText())) {
      return Optional.empty();
    }

    // Check if this looks like SQL syntax in PPL context
    String remaining = context.getRemainingQuery();
    if (remaining.matches("(?i)^\\s*\\*\\s+from\\s+.*")) {
      return Optional.of(
        "PPL uses 'source=index | fields *' instead of 'SELECT * FROM index'");
    }

    return Optional.empty();
  }

  @Override
  public int getPriority() {
    return 20;
  }
}
```

### 3. Registry and Manager

```java
/**
 * Manages all syntax error suggestion providers.
 */
public class SyntaxErrorSuggestionRegistry {
  private static final List<SyntaxErrorSuggestionProvider> providers = new ArrayList<>();

  static {
    // Register all providers
    register(new IsNullSuggestionProvider());
    register(new SelectStarSuggestionProvider());
    register(new OrderByLimitSuggestionProvider());
    // ... more providers

    // Sort by priority
    providers.sort(Comparator.comparingInt(SyntaxErrorSuggestionProvider::getPriority));
  }

  public static void register(SyntaxErrorSuggestionProvider provider) {
    providers.add(provider);
  }

  /**
   * Find the first matching suggestion from registered providers.
   */
  public static Optional<String> findSuggestion(SyntaxErrorContext context) {
    for (SyntaxErrorSuggestionProvider provider : providers) {
      Optional<String> suggestion = provider.getSuggestion(context);
      if (suggestion.isPresent()) {
        return suggestion;
      }
    }
    return Optional.empty();
  }
}
```

### 4. Updated Error Listener

The error listener becomes much simpler:

```java
public class SyntaxAnalysisErrorListener extends BaseErrorListener {

  @Override
  public void syntaxError(...) {
    // ... existing code to build ErrorReport ...

    // Check for custom suggestions using registry
    SyntaxErrorContext context = new SyntaxErrorContext(
      offendingToken, tokens, query, e);

    Optional<String> customSuggestion =
      SyntaxErrorSuggestionRegistry.findSuggestion(context);

    if (customSuggestion.isPresent()) {
      reportBuilder.suggestion(customSuggestion.get());
    } else if (e != null) {
      // Fall back to expected tokens
      reportBuilder.suggestion(getExpectedTokensSuggestion(e));
    }

    throw reportBuilder.build();
  }
}
```

## Benefits of This Architecture

### 1. **Separation of Concerns**
- Error listener focuses on error detection and reporting
- Pattern providers focus on specific syntax patterns
- Easy to understand and maintain each piece

### 2. **Testability**
```java
@Test
public void testIsNullSuggestion() {
  SyntaxErrorContext context = createContext(
    "source=logs | where field is null",
    "is", 28);

  IsNullSuggestionProvider provider = new IsNullSuggestionProvider();
  Optional<String> suggestion = provider.getSuggestion(context);

  assertTrue(suggestion.isPresent());
  assertTrue(suggestion.get().contains("isnull(field)"));
}
```

### 3. **Extensibility**
- Add new patterns by creating new provider classes
- No need to modify existing code
- Can be loaded via configuration or plugin system

### 4. **Prioritization**
- Control which patterns are checked first
- More common patterns get higher priority
- Can disable/enable providers dynamically

### 5. **Configuration**
```yaml
# error-suggestions.yaml
providers:
  - class: IsNullSuggestionProvider
    enabled: true
    priority: 10
  - class: SelectStarSuggestionProvider
    enabled: true
    priority: 20
  - class: CustomProvider
    enabled: false
```

## Additional Pattern Ideas

With this architecture, adding new patterns is straightforward:

### SQL-to-PPL Translation Patterns
```java
// SELECT col FROM table WHERE x > 10 ORDER BY col LIMIT 5
// → source=table | where x > 10 | sort col | head 5
public class SqlToPplSuggestionProvider { ... }
```

### Common Typos
```java
// "sorce" → "source"
// "feild" → "field"
public class CommonTypoSuggestionProvider { ... }
```

### Operator Confusion
```java
// "field == value" → "field = value" (PPL uses single =)
// "field && other" → "field AND other"
public class OperatorSuggestionProvider { ... }
```

### Function Call Patterns
```java
// "UPPER(field)" when expecting "upper(field)" (case sensitivity)
// "field.length()" → "len(field)"
public class FunctionSyntaxSuggestionProvider { ... }
```

## Alternative: Rule-Based DSL

For even more flexibility, could define patterns in a DSL:

```yaml
patterns:
  - name: is_not_null
    priority: 10
    match:
      offending_token: "is"
      look_ahead: "not null"
    suggestion: "PPL doesn't support 'IS NOT NULL' syntax. Use isnotnull(${field}) function instead."
    field_extraction:
      direction: backward
      stop_at: ["where", "and", "or"]

  - name: is_null
    priority: 10
    match:
      offending_token: "is"
      look_ahead: "null"
    suggestion: "PPL doesn't support 'IS NULL' syntax. Use isnull(${field}) function instead."
    field_extraction:
      direction: backward
      stop_at: ["where", "and", "or"]
```

Benefits:
- No code changes for new patterns
- Can be updated via configuration
- Non-developers can contribute patterns
- A/B test different suggestion wording

Drawbacks:
- More complex implementation
- Less type safety
- Harder to debug

## Recommendation

Start with the **Pattern Registry approach**:
1. More maintainable than current hardcoded approach
2. Good balance of flexibility and simplicity
3. Can evolve to DSL later if needed
4. Provides clear extension points

The registry can be implemented incrementally:
1. Move existing "is null" pattern to IsNullSuggestionProvider
2. Add registry infrastructure
3. Add new patterns as separate providers
4. Eventually consider DSL if pattern count grows significantly

## Migration Path

1. **Phase 1**: Extract current pattern to IsNullSuggestionProvider
2. **Phase 2**: Create SyntaxErrorContext and registry infrastructure
3. **Phase 3**: Add 2-3 more providers to validate approach
4. **Phase 4**: Add configuration support
5. **Phase 5**: Consider DSL if needed

Each phase can be done incrementally without breaking existing functionality.
