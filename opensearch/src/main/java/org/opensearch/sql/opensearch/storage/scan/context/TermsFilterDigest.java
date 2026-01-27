/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.storage.scan.context;

import java.util.List;

/**
 * Digest for terms filter push-down (IN clause optimization for join keys). Used to push down a
 * list of join key values as an OpenSearch terms query.
 */
public record TermsFilterDigest(String fieldName, List<Object> values) {
  @Override
  public String toString() {
    return fieldName + " IN (" + values.size() + " values)";
  }
}
