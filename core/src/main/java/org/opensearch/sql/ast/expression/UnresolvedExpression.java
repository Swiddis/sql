/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.ast.expression;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.opensearch.sql.ast.AbstractNodeVisitor;
import org.opensearch.sql.ast.Node;

@EqualsAndHashCode(callSuper = false)
@ToString
public abstract class UnresolvedExpression extends Node {

  /** Default constructor for expressions without position information. */
  protected UnresolvedExpression() {
    super();
  }

  /**
   * Constructor with position information.
   *
   * @param line Line number (1-based)
   * @param column Column number (0-based)
   */
  protected UnresolvedExpression(int line, int column) {
    super(line, column);
  }

  @Override
  public <T, C> T accept(AbstractNodeVisitor<T, C> nodeVisitor, C context) {
    return nodeVisitor.visitChildren(this, context);
  }
}
