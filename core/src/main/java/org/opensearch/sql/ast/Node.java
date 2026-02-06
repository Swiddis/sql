/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.ast;

import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** AST node. */
@EqualsAndHashCode
@ToString
public abstract class Node {

  /** Line number in the source query where this node appears. -1 if position is not available. */
  @Getter private final int line;

  /** Column number in the source query where this node appears. -1 if position is not available. */
  @Getter private final int column;

  /** Default constructor for nodes without position information. */
  protected Node() {
    this(-1, -1);
  }

  /**
   * Constructor with position information.
   *
   * @param line Line number (1-based)
   * @param column Column number (0-based)
   */
  protected Node(int line, int column) {
    this.line = line;
    this.column = column;
  }

  /** Check if this node has position information available. */
  public boolean hasPosition() {
    return line >= 0 && column >= 0;
  }

  public <R, C> R accept(AbstractNodeVisitor<R, C> visitor, C context) {
    return visitor.visitChildren(this, context);
  }

  public List<? extends Node> getChild() {
    return null;
  }
}
