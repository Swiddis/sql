/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.plan.rel;

/**
 * A table {@code collect} can write to. Only tables that implement it are accepted as a collect
 * destination; anything else (a system catalog table, a REST source) is refused at analysis.
 */
public interface CollectTarget {

  /**
   * Resolves the destination name the way a write would (aliases, date math) and throws a {@link
   * org.opensearch.sql.exception.SemanticCheckException} if it lands on a system or hidden index.
   */
  void checkCollectTarget();
}
