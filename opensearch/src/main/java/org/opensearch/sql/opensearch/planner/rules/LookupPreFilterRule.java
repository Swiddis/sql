/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.planner.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.sql.opensearch.request.OpenSearchRequest;
import org.opensearch.sql.opensearch.request.OpenSearchRequestBuilder;
import org.opensearch.sql.opensearch.storage.OpenSearchIndex;
import org.opensearch.sql.opensearch.storage.scan.CalciteLogicalIndexScan;
import org.opensearch.sql.opensearch.storage.scan.OpenSearchIndexEnumerator;
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

    // Skip if left scan already has a terms filter (already optimized)
    PushDownContext leftContext = leftScan.getPushDownContext();
    if (leftContext != null) {
      boolean hasTermsFilter =
          leftContext.stream().anyMatch(op -> op.type() == PushDownType.TERMS_FILTER);
      if (hasTermsFilter) {
        return false;
      }
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

    // Pre-execute the right scan to extract distinct join key values
    List<Object> joinKeyValues;
    try {
      joinKeyValues = preExecuteAndExtractJoinKeys(rightScan, rightJoinKeyField);
      LOG.info(
          "[LookupPreFilterRule] Pre-executed lookup scan, extracted {} distinct join key values",
          joinKeyValues.size());
    } catch (Exception e) {
      LOG.warn(
          "[LookupPreFilterRule] Failed to pre-execute right scan, skipping optimization: {}",
          e.getMessage());
      return;
    }

    // Skip optimization if no join keys found
    if (joinKeyValues.isEmpty()) {
      LOG.debug("[LookupPreFilterRule] No join key values found, skipping optimization");
      return;
    }

    // Create new left scan with terms filter injected
    CalciteLogicalIndexScan newLeftScan = leftScan.copy();
    newLeftScan
        .getPushDownContext()
        .add(
            PushDownType.TERMS_FILTER,
            new TermsFilterDigest(leftJoinKeyField, joinKeyValues),
            (OSRequestBuilderAction)
                requestBuilder -> {
                  // Push down terms query to OpenSearch
                  requestBuilder.pushDownFilterForCalcite(
                      QueryBuilders.termsQuery(leftJoinKeyField, joinKeyValues));
                  LOG.debug(
                      "Pushed down terms filter for field: {} with {} values",
                      leftJoinKeyField,
                      joinKeyValues.size());
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

  /**
   * Pre-execute the right scan (lookup) and extract distinct join key values.
   *
   * @param rightScan the lookup table scan with filters
   * @param joinKeyField the field name to extract join key values from
   * @return list of distinct join key values
   */
  private List<Object> preExecuteAndExtractJoinKeys(
      CalciteLogicalIndexScan rightScan, String joinKeyField) {
    // Get the OpenSearch index and create request builder
    OpenSearchIndex osIndex = rightScan.getOsIndex();
    PushDownContext pushDownContext = rightScan.getPushDownContext();
    OpenSearchRequestBuilder requestBuilder = pushDownContext.createRequestBuilder();

    // Build the request with only the join key field for efficiency
    OpenSearchRequest request = osIndex.buildRequest(requestBuilder);

    // Create enumerator to iterate through results
    OpenSearchIndexEnumerator enumerator =
        new OpenSearchIndexEnumerator(
            osIndex.getClient(),
            List.of(joinKeyField), // Only fetch the join key field
            requestBuilder.getMaxResponseSize(),
            requestBuilder.getMaxResultWindow(),
            osIndex.getQueryBucketSize(),
            request,
            osIndex.createOpenSearchResourceMonitor());

    // Collect distinct join key values
    Set<Object> distinctKeys = new HashSet<>();
    try {
      while (enumerator.moveNext()) {
        Object keyValue = enumerator.current();
        // Filter out null values
        if (keyValue != null) {
          distinctKeys.add(keyValue);
        }
        // Safety check: if we exceed threshold, stop collecting
        if (distinctKeys.size() > MAX_TERMS_FOR_OPTIMIZATION) {
          LOG.warn(
              "[LookupPreFilterRule] Exceeded threshold while collecting keys, stopping at {}",
              MAX_TERMS_FOR_OPTIMIZATION);
          break;
        }
      }
    } finally {
      enumerator.close();
    }

    return new ArrayList<>(distinctKeys);
  }
}
