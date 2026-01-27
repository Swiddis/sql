/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.planner.rules;

import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.sql.opensearch.storage.scan.CalciteLogicalIndexScan;
import org.opensearch.sql.opensearch.storage.scan.context.OSRequestBuilderAction;
import org.opensearch.sql.opensearch.storage.scan.context.PushDownContext;
import org.opensearch.sql.opensearch.storage.scan.context.PushDownType;
import org.opensearch.sql.opensearch.storage.scan.context.TermsFilterDigest;

/**
 * POC rule to detect lookups with discriminators and additional filters, and optimize by
 * pre-filtering the main table using terms query.
 *
 * <p>Pattern to match: - LEFT JOIN between two CalciteLogicalIndexScan nodes - Right side (lookup)
 * has filters including discriminator - Join condition is simple equality on a field
 */
public class LookupPreFilterRule extends RelOptRule {
  private static final Logger LOG = LogManager.getLogger(LookupPreFilterRule.class);
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
    Join join = call.rel(0);
    CalciteLogicalIndexScan leftScan = call.rel(1);
    CalciteLogicalIndexScan rightScan = call.rel(2);

    // Extract join key field information
    RexCall joinCondition = (RexCall) join.getCondition();
    if (joinCondition.getOperands().size() != 2) {
      LOG.debug("Join condition has unexpected number of operands: {}", joinCondition);
      return;
    }

    // Extract field references from join condition (e.g., $0 = $1)
    RexNode leftOperand = joinCondition.getOperands().get(0);
    RexNode rightOperand = joinCondition.getOperands().get(1);

    if (!(leftOperand instanceof RexInputRef) || !(rightOperand instanceof RexInputRef)) {
      LOG.debug("Join condition operands are not simple field references");
      return;
    }

    RexInputRef leftRef = (RexInputRef) leftOperand;
    RexInputRef rightRef = (RexInputRef) rightOperand;

    // Determine which reference is from left scan and which is from right scan
    int leftFieldCount = leftScan.getRowType().getFieldCount();
    String leftJoinKeyField;
    String rightJoinKeyField;

    if (leftRef.getIndex() < leftFieldCount && rightRef.getIndex() >= leftFieldCount) {
      // leftRef is from left scan, rightRef is from right scan
      leftJoinKeyField = leftScan.getRowType().getFieldNames().get(leftRef.getIndex());
      rightJoinKeyField =
          rightScan.getRowType().getFieldNames().get(rightRef.getIndex() - leftFieldCount);
    } else if (rightRef.getIndex() < leftFieldCount && leftRef.getIndex() >= leftFieldCount) {
      // rightRef is from left scan, leftRef is from right scan
      leftJoinKeyField = leftScan.getRowType().getFieldNames().get(rightRef.getIndex());
      rightJoinKeyField =
          rightScan.getRowType().getFieldNames().get(leftRef.getIndex() - leftFieldCount);
    } else {
      LOG.debug("Could not determine join key fields from condition: {}", joinCondition);
      return;
    }

    // Estimate cardinality of right scan (lookup with filters)
    RelMetadataQuery mq = call.getMetadataQuery();
    Double rightRowCount = mq.getRowCount(rightScan);

    if (rightRowCount == null || rightRowCount > MAX_TERMS_FOR_OPTIMIZATION) {
      LOG.debug(
          "Right side cardinality {} exceeds threshold {}, skipping optimization",
          rightRowCount,
          MAX_TERMS_FOR_OPTIMIZATION);
      return;
    }

    LOG.info(
        "[LookupPreFilterRule] Applying optimization: join keys: left={}, right={}, estimated"
            + " lookup cardinality={}",
        leftJoinKeyField,
        rightJoinKeyField,
        rightRowCount);

    // POC: For now, we'll create a placeholder transformation
    // In a full implementation, we would:
    // 1. Pre-execute the right scan to get actual distinct join key values
    // 2. Use those values in the terms filter
    // For POC, we'll create an empty terms filter as a marker

    // TODO: Pre-execute right scan and extract distinct join key values
    // For now, use placeholder empty list
    List<Object> joinKeyValues = new ArrayList<>();

    // Create new left scan with terms filter injected
    CalciteLogicalIndexScan newLeftScan = leftScan.copy();
    newLeftScan
        .getPushDownContext()
        .add(
            PushDownType.TERMS_FILTER,
            new TermsFilterDigest(leftJoinKeyField, joinKeyValues),
            (OSRequestBuilderAction)
                requestBuilder -> {
                  // TODO: Implement pushDownTermsFilter in OpenSearchRequestBuilder
                  // For POC, this is a no-op marker
                  LOG.debug("Terms filter push-down for field: {}", leftJoinKeyField);
                });

    // Create new join with optimized left scan
    Join newJoin =
        join.copy(
            join.getTraitSet(),
            join.getCondition(),
            newLeftScan,
            rightScan,
            join.getJoinType(),
            join.isSemiJoinDone());

    LOG.info("[LookupPreFilterRule] Transformation applied, returning optimized plan");
    call.transformTo(newJoin);
  }
}
