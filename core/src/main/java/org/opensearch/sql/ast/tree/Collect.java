/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.ast.tree;

import com.google.common.collect.ImmutableList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.opensearch.sql.ast.AbstractNodeVisitor;
import org.opensearch.sql.ast.expression.QualifiedName;

/**
 * AST node for the {@code collect} command: a terminal write sink appending pipeline rows to a
 * pre-existing destination index. See the collect RFC for design.
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
@AllArgsConstructor
public class Collect extends UnresolvedPlan {

  private UnresolvedPlan child;

  /** The destination as parsed, like a {@code source=} name; resolved and checked at analysis. */
  private final QualifiedName destination;

  /**
   * Columns whose values are hashed into a deterministic document {@code _id}, so re-running the
   * same collect overwrites instead of appending. Empty means auto-generated (append-only) ids.
   */
  private List<String> idFields = ImmutableList.of();

  /** Dry-run: pass rows through, but do not write to the destination. */
  private boolean testmode = false;

  /** The destination name as written (parts joined with dots), for messages. */
  public String getIndexName() {
    return destination.toString();
  }

  @Override
  public Collect attach(UnresolvedPlan child) {
    this.child = child;
    return this;
  }

  @Override
  public List<UnresolvedPlan> getChild() {
    return this.child == null ? ImmutableList.of() : ImmutableList.of(this.child);
  }

  @Override
  public <T, C> T accept(AbstractNodeVisitor<T, C> nodeVisitor, C context) {
    return nodeVisitor.visitCollect(this, context);
  }
}
