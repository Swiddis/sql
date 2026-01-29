/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.planner.rules;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rex.RexLiteral;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.sql.opensearch.storage.scan.CalciteLogicalIndexScan;
import org.opensearch.sql.opensearch.storage.scan.context.LimitDigest;
import org.opensearch.sql.opensearch.storage.scan.context.OSRequestBuilderAction;
import org.opensearch.sql.opensearch.storage.scan.context.PushDownContext;
import org.opensearch.sql.opensearch.storage.scan.context.PushDownType;

/**
 * Rule to push LIMIT through LEFT JOIN to the left (probe) side scan.
 *
 * <p>For LEFT JOIN, each left row appears exactly once in the output (either matched with a right
 * row or with NULLs). Therefore, LIMIT on the join output is equivalent to LIMIT on the left input.
 *
 * <p>This rule matches the pattern: LogicalSort -> LogicalFilter -> LogicalProject -> Join
 */
public class LimitLeftJoinRule extends RelOptRule {
  private static final Logger LOG = LogManager.getLogger(LimitLeftJoinRule.class);

  public static final LimitLeftJoinRule INSTANCE = new LimitLeftJoinRule();

  private LimitLeftJoinRule() {
    super(
        operand(
            LogicalSort.class,
            operand(
                LogicalFilter.class,
                operand(
                    LogicalProject.class,
                    operand(
                        Join.class,
                        operand(CalciteLogicalIndexScan.class, any()),
                        operand(CalciteLogicalIndexScan.class, any()))))),
        "LimitLeftJoinRule");
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    LogicalSort sort = call.rel(0);
    LogicalFilter filter = call.rel(1);
    LogicalProject project = call.rel(2);
    Join join = call.rel(3);
    CalciteLogicalIndexScan leftScan = call.rel(4);

    // Only match if this is a LIMIT (has fetch but no sort collation)
    if (sort.fetch == null || !sort.getCollation().getFieldCollations().isEmpty()) {
      return false;
    }

    // Only optimize LEFT joins
    if (join.getJoinType() != JoinRelType.LEFT) {
      return false;
    }

    // Skip if left scan already has a limit pushed down (avoid infinite loop)
    PushDownContext leftContext = leftScan.getPushDownContext();
    return leftContext == null || !leftContext.isLimitPushed();
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    LogicalSort sort = call.rel(0);
    LogicalFilter filter = call.rel(1);
    LogicalProject project = call.rel(2);
    Join join = call.rel(3);
    CalciteLogicalIndexScan leftScan = call.rel(4);
    CalciteLogicalIndexScan rightScan = call.rel(5);

    // Extract limit and offset
    Integer limit = extractLimitValue(sort.fetch);
    Integer offset = extractOffsetValue(sort.offset);

    if (limit == null || offset == null) {
      return;
    }

    LOG.info(
        "[LimitLeftJoinRule] Pushing limit {} offset {} to left scan: {}",
        limit,
        offset,
        leftScan.getTable().getQualifiedName());

    // Create new left scan with limit pushed down
    CalciteLogicalIndexScan newLeftScan = leftScan.copy();
    LimitDigest limitDigest = new LimitDigest(limit, offset);
    newLeftScan
        .getPushDownContext()
        .add(
            PushDownType.LIMIT,
            limitDigest,
            (OSRequestBuilderAction) requestBuilder -> requestBuilder.pushDownLimit(limit, offset));

    // Create new join with optimized left scan
    Join newJoin =
        join.copy(
            join.getTraitSet(),
            join.getCondition(),
            newLeftScan,
            rightScan,
            join.getJoinType(),
            join.isSemiJoinDone());

    // Reconstruct the tree: Sort -> Filter -> Project -> newJoin
    // We MUST keep the LogicalSort to ensure EnumerableLimit is added after the join
    // The isLimitPushed() check in matches() should prevent LimitIndexScanRule from
    // running again on this scan
    LogicalProject newProject =
        (LogicalProject)
            project.copy(
                project.getTraitSet(), newJoin, project.getProjects(), project.getRowType());

    LogicalFilter newFilter =
        (LogicalFilter) filter.copy(filter.getTraitSet(), newProject, filter.getCondition());

    LogicalSort newSort =
        (LogicalSort)
            sort.copy(sort.getTraitSet(), newFilter, sort.getCollation(), sort.offset, sort.fetch);

    call.transformTo(newSort);
    LOG.info("[LimitLeftJoinRule] Successfully pushed limit to left scan");
  }

  private static Integer extractLimitValue(org.apache.calcite.rex.RexNode fetch) {
    if (fetch instanceof RexLiteral) {
      return ((RexLiteral) fetch).getValueAs(Integer.class);
    }
    return null;
  }

  private static Integer extractOffsetValue(org.apache.calcite.rex.RexNode offset) {
    if (offset == null) {
      return 0;
    }
    if (offset instanceof RexLiteral) {
      return ((RexLiteral) offset).getValueAs(Integer.class);
    }
    return null;
  }
}
