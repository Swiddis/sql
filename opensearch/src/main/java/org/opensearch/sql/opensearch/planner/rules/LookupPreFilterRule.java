/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.planner.rules;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.opensearch.sql.opensearch.storage.scan.CalciteLogicalIndexScan;
import org.opensearch.sql.opensearch.storage.scan.context.PushDownContext;
import org.opensearch.sql.opensearch.storage.scan.context.PushDownType;

/**
 * POC rule to detect lookups with discriminators and additional filters, and optimize by
 * pre-filtering the main table using terms query.
 *
 * <p>Pattern to match: - LEFT JOIN between two CalciteLogicalIndexScan nodes - Right side (lookup)
 * has filters including discriminator - Join condition is simple equality on a field
 */
public class LookupPreFilterRule extends RelOptRule {
  public static final LookupPreFilterRule INSTANCE = new LookupPreFilterRule();

  private static final int MAX_TERMS_FOR_OPTIMIZATION = 100;

  private LookupPreFilterRule() {
    super(
        operand(
            Join.class,
            operand(CalciteLogicalIndexScan.class, any()),
            operand(CalciteLogicalIndexScan.class, any())),
        "LookupPreFilterRule");
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    Join join = call.rel(0);
    CalciteLogicalIndexScan leftScan = call.rel(1);
    CalciteLogicalIndexScan rightScan = call.rel(2);

    // Only optimize LEFT joins
    if (join.getJoinType() != JoinRelType.LEFT) {
      return false;
    }

    // Check if right side (lookup) has filters
    PushDownContext rightContext = rightScan.getPushDownContext();
    if (rightContext == null || rightContext.isEmpty()) {
      return false;
    }

    // Check if there's a filter pushed down to the right side
    boolean hasFilter =
        rightContext.stream()
            .anyMatch(op -> op.type() == PushDownType.FILTER || op.type() == PushDownType.SCRIPT);

    if (!hasFilter) {
      return false;
    }

    // Check if join condition is simple equality (e.g., a.key = b.key)
    RexNode condition = join.getCondition();
    if (!(condition instanceof RexCall)) {
      return false;
    }

    RexCall call2 = (RexCall) condition;
    return call2.getKind() == SqlKind.EQUALS;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    // POC: For now, just log that we detected the pattern
    // In a full implementation, we would:
    // 1. Extract the lookup table filters
    // 2. Estimate cardinality of filtered lookup results
    // 3. If small enough, pre-execute lookup scan
    // 4. Extract distinct join key values
    // 5. Inject terms filter into left scan
    // 6. Return optimized plan

    Join join = call.rel(0);
    CalciteLogicalIndexScan rightScan = call.rel(2);

    System.out.println("[LookupPreFilterRule] Detected optimization opportunity:");
    System.out.println("  Join type: " + join.getJoinType());
    System.out.println("  Lookup table: " + rightScan.getTable().getQualifiedName());
    System.out.println("  Push-down context: " + rightScan.getPushDownContext());

    // Don't transform for now - just detect
    // call.transformTo(...);
  }
}
