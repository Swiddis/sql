/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.common.antlr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.opensearch.sql.common.error.ErrorCode;
import org.opensearch.sql.common.error.ErrorReport;

/**
 * Syntax analysis error listener that handles any syntax error by throwing exception with useful
 * information.
 */
public class SyntaxAnalysisErrorListener extends BaseErrorListener {
  // Show up to this many characters before the offending token in the query.
  private static final int CONTEXT_TRUNCATION_THRESHOLD = 20;
  // Avoid presenting too many alternatives when many are available.
  private static final int SUGGESTION_TRUNCATION_THRESHOLD = 5;

  @Override
  public void syntaxError(
      Recognizer<?, ?> recognizer,
      Object offendingSymbol,
      int line,
      int charPositionInLine,
      String msg,
      RecognitionException e) {

    CommonTokenStream tokens = (CommonTokenStream) recognizer.getInputStream();
    Token offendingToken = (Token) offendingSymbol;
    String query = tokens.getText();

    // Build the original error message for backward compatibility
    String details =
        String.format(
            Locale.ROOT,
            "[%s] is not a valid term at this part of the query: '%s' <-- HERE. %s",
            getOffendingText(offendingToken),
            truncateQueryAtOffendingToken(query, offendingToken),
            getDetails(recognizer, msg, e));

    // Create a SyntaxCheckException as the underlying cause
    SyntaxCheckException cause = new SyntaxCheckException(details);

    // Build position information
    Map<String, Object> position = new HashMap<>();
    position.put("line", line);
    position.put("column", charPositionInLine);

    // Build ErrorReport with structured context
    ErrorReport.Builder reportBuilder =
        ErrorReport.wrap(cause)
            .code(ErrorCode.SYNTAX_ERROR)
            .location("while parsing the query")
            .context("query", query)
            .context("position", position)
            .context("offending_token", getOffendingText(offendingToken));

    // Check for common SQL syntax patterns and provide helpful suggestions
    String customSuggestion = getCustomSuggestion(offendingToken, tokens);
    if (customSuggestion != null) {
      reportBuilder.suggestion(customSuggestion);
    } else if (e != null) {
      // Add expected tokens as suggestion if available
      IntervalSet possibleContinuations = e.getExpectedTokens();
      List<String> suggestions = topSuggestions(recognizer, possibleContinuations);
      if (!suggestions.isEmpty()) {
        String suggestionText =
            possibleContinuations.size() > SUGGESTION_TRUNCATION_THRESHOLD
                ? String.format(
                    "Expected one of %d possible tokens. Examples: %s",
                    possibleContinuations.size(), String.join(", ", suggestions))
                : "Expected tokens: " + String.join(", ", suggestions);
        reportBuilder.suggestion(suggestionText);
      }
    }

    throw reportBuilder.build();
  }

  /**
   * Detect common SQL syntax patterns and provide custom suggestions.
   *
   * @param offendingToken The token that caused the error
   * @param tokens The token stream
   * @return Custom suggestion text, or null if no pattern detected
   */
  private String getCustomSuggestion(Token offendingToken, CommonTokenStream tokens) {
    String offendingText = offendingToken.getText().toLowerCase();
    String query = tokens.getText();

    // Detect "is [not] null" pattern by examining the query string
    if ("is".equals(offendingText)) {
      // Get the position in the query after "is"
      int isEndPos = offendingToken.getStopIndex() + 1;

      // Look ahead in the query string to see what follows "is"
      String remainingQuery = query.substring(isEndPos).trim().toLowerCase();

      if (remainingQuery.startsWith("not null") || remainingQuery.startsWith("not\tnull")) {
        // Get the field name before "is"
        String fieldName = getFieldNameBeforeIs(offendingToken, tokens.getTokens());
        return String.format(
            "PPL doesn't support 'IS NOT NULL' syntax. Use isnotnull(%s) function instead.",
            fieldName);
      } else if (remainingQuery.startsWith("null")) {
        // Get the field name before "is"
        String fieldName = getFieldNameBeforeIs(offendingToken, tokens.getTokens());
        return String.format(
            "PPL doesn't support 'IS NULL' syntax. Use isnull(%s) function instead.", fieldName);
      }
    }

    return null;
  }

  /**
   * Try to extract the field name that appears before the "is" keyword.
   *
   * @param isToken The "is" token
   * @param allTokens All tokens in the stream
   * @return The field name, or "field" as a placeholder
   */
  private String getFieldNameBeforeIs(Token isToken, List<Token> allTokens) {
    int isIndex = isToken.getTokenIndex();

    // Look backwards to find the field name, skipping whitespace tokens
    for (int i = isIndex - 1; i >= 0; i--) {
      Token token = allTokens.get(i);
      String tokenText = token.getText();

      // Skip whitespace and hidden tokens
      if (tokenText.trim().isEmpty()) {
        continue;
      }

      // If we hit a keyword or operator, stop looking
      if (tokenText.matches("(?i)(where|and|or|not|\\||,|\\(|\\))")) {
        break;
      }

      // Return the first non-whitespace, non-keyword token as the field name
      return tokenText;
    }

    return "field";
  }

  private String getOffendingText(Token offendingToken) {
    return offendingToken.getText();
  }

  private String truncateQueryAtOffendingToken(String query, Token offendingToken) {
    int contextStartIndex = offendingToken.getStartIndex() - CONTEXT_TRUNCATION_THRESHOLD;
    if (contextStartIndex < 3) { // The ellipses won't save us anything below the first 4 characters
      return query.substring(0, offendingToken.getStopIndex() + 1);
    }
    return "..." + query.substring(contextStartIndex, offendingToken.getStopIndex() + 1);
  }

  private List<String> topSuggestions(Recognizer<?, ?> recognizer, IntervalSet continuations) {
    Vocabulary vocab = recognizer.getVocabulary();
    List<String> tokenNames = new ArrayList<>(SUGGESTION_TRUNCATION_THRESHOLD);
    for (int tokenType :
        continuations
            .toList()
            .subList(0, Math.min(continuations.size(), SUGGESTION_TRUNCATION_THRESHOLD))) {
      tokenNames.add(vocab.getDisplayName(tokenType));
    }
    return tokenNames;
  }

  private String getDetails(Recognizer<?, ?> recognizer, String msg, RecognitionException ex) {
    if (ex == null) {
      // According to the ANTLR docs, ex == null means the parser was able to recover from the
      // error.
      // In such cases, `msg` includes the raw error information we care about.
      return msg;
    }

    IntervalSet possibleContinuations = ex.getExpectedTokens();
    List<String> suggestions = topSuggestions(recognizer, possibleContinuations);

    StringBuilder details = new StringBuilder("Expecting ");
    if (possibleContinuations.size() > SUGGESTION_TRUNCATION_THRESHOLD) {
      details
          .append("one of ")
          .append(possibleContinuations.size())
          .append(" possible tokens. Some examples: ")
          .append(String.join(", ", suggestions))
          .append(", ...");
    } else {
      details.append("tokens: ").append(String.join(", ", suggestions));
    }
    return details.toString();
  }
}
