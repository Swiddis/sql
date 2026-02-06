/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.common.error;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Error report that wraps exceptions and accumulates contextual information as errors bubble up
 * through system layers.
 *
 * <p>Inspired by Rust's anyhow/eyre libraries, this class allows each layer to add context without
 * modifying the original exception message.
 *
 * <p>Example usage:
 *
 * <pre>
 * try {
 *   resolveField(fieldName);
 * } catch (IllegalArgumentException e) {
 *   throw ErrorReport.wrap(e)
 *     .code(ErrorCode.FIELD_NOT_FOUND)
 *     .location("while resolving fields in the index mapping")
 *     .suggestion("Did you mean: '" + suggestedField + "'?")
 *     .context("index_pattern", indexPattern)
 *     .context("position", cursorPosition)
 *     .build();
 * }
 * </pre>
 */
public class ErrorReport extends RuntimeException {

  private final Throwable cause;
  private final ErrorCode code;
  private final List<String> locationChain;
  private final Map<String, Object> context;
  private final String suggestion;
  private final String details;

  private ErrorReport(Builder builder) {
    super(builder.cause.getMessage(), builder.cause);
    this.cause = builder.cause;
    this.code = builder.code;
    this.locationChain = new ArrayList<>(builder.locationChain);
    this.context = new LinkedHashMap<>(builder.context);
    this.suggestion = builder.suggestion;
    this.details = builder.details;
  }

  /**
   * Wraps an exception with an error report builder.
   *
   * @param cause The underlying exception
   * @return A builder for constructing the error report
   */
  public static Builder wrap(Throwable cause) {
    return new Builder(cause);
  }

  public ErrorCode getCode() {
    return code;
  }

  public List<String> getLocationChain() {
    return new ArrayList<>(locationChain);
  }

  public Map<String, Object> getContext() {
    return new LinkedHashMap<>(context);
  }

  public String getSuggestion() {
    return suggestion;
  }

  public String getDetails() {
    return details;
  }

  /** Get the original exception type name. */
  public String getExceptionType() {
    return cause.getClass().getSimpleName();
  }

  /** Builder for constructing error reports with contextual information. */
  public static class Builder {
    private final Throwable cause;
    private ErrorCode code = ErrorCode.UNKNOWN;
    private final List<String> locationChain = new ArrayList<>();
    private final Map<String, Object> context = new LinkedHashMap<>();
    private String suggestion = null;
    private String details = null;

    private Builder(Throwable cause) {
      this.cause = cause;
      // Default details to the original exception message
      this.details = cause.getLocalizedMessage();
    }

    /** Set the machine-readable error code. */
    public Builder code(ErrorCode code) {
      this.code = code;
      return this;
    }

    /**
     * Add a location to the chain describing where the error occurred. Locations are added in order
     * from innermost to outermost layer.
     *
     * @param location Description like "while resolving fields in index mapping"
     */
    public Builder location(String location) {
      this.locationChain.add(location);
      return this;
    }

    /**
     * Add structured context data (index name, query, position, etc).
     *
     * @param key Context key
     * @param value Context value (will be converted to string for serialization)
     */
    public Builder context(String key, Object value) {
      this.context.put(key, value);
      return this;
    }

    /**
     * Set a suggestion for how to fix the error.
     *
     * @param suggestion User-facing suggestion like "Did you mean: 'foo'?"
     */
    public Builder suggestion(String suggestion) {
      this.suggestion = suggestion;
      return this;
    }

    /**
     * Override the default details message. By default, uses the wrapped exception's message.
     *
     * @param details Custom details message
     */
    public Builder details(String details) {
      this.details = details;
      return this;
    }

    /**
     * Build and throw the error report as an exception.
     *
     * @return Never returns; always throws
     * @throws ErrorReport The constructed error report
     */
    public ErrorReport build() {
      return new ErrorReport(this);
    }
  }
}
