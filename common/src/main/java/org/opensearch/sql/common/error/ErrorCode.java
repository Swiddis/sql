/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.common.error;

/**
 * Machine-readable error codes for categorizing exceptions. These codes help clients handle
 * specific error types programmatically.
 */
public enum ErrorCode {
  /** Field not found in the index mapping */
  FIELD_NOT_FOUND,

  /** Syntax error in query parsing */
  SYNTAX_ERROR,

  /** Ambiguous field reference (multiple fields with same name) */
  AMBIGUOUS_FIELD,

  /** Generic semantic validation error */
  SEMANTIC_ERROR,

  /** Expression evaluation failed */
  EVALUATION_ERROR,

  /** Unknown or unclassified error */
  UNKNOWN
}
