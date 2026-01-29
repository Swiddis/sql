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
import org.opensearch.sql.data.type.ExprType;
import org.opensearch.sql.opensearch.data.type.OpenSearchTextType;
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

  private static final int MAX_TERMS_FOR_OPTIMIZATION = 100000;

  private LookupPreFilterRule() {
    super(
        operand(
            org.apache.calcite.rel.logical.LogicalFilter.class,
            operand(
                org.apache.calcite.rel.logical.LogicalProject.class,
                operand(
                    Join.class,
                    operand(CalciteLogicalIndexScan.class, any()),
                    operand(CalciteLogicalIndexScan.class, any())))),
        "LookupPreFilterRule");
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    // Pattern: LogicalFilter -> LogicalProject -> Join -> leftScan, rightScan
    Join join = call.rel(2);
    CalciteLogicalIndexScan leftScan = call.rel(3);
    CalciteLogicalIndexScan rightScan = call.rel(4);

    // Only optimize LEFT joins
    if (join.getJoinType() != JoinRelType.LEFT) {
      return false;
    }

    // Check if right side (lookup) has filters
    PushDownContext rightContext = rightScan.getPushDownContext();
    if (rightContext == null || rightContext.isEmpty()) {
      return false;
    }

    // Skip if left scan already has a terms filter (prevents infinite loop)
    // After we apply the optimization, the transformed plan would still match this rule's pattern,
    // so we need to check if we've already optimized this join
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
    // Pattern: LogicalFilter -> LogicalProject -> Join -> leftScan, rightScan
    org.apache.calcite.rel.logical.LogicalFilter filter = call.rel(0);
    org.apache.calcite.rel.logical.LogicalProject project = call.rel(1);
    Join join = call.rel(2);
    CalciteLogicalIndexScan leftScan = call.rel(3);
    CalciteLogicalIndexScan rightScan = call.rel(4);

    LOG.info("[LookupPreFilterRule] Rule matched, analyzing join optimization opportunity");
    LOG.info("[LookupPreFilterRule] Left scan: {}", leftScan.getTable().getQualifiedName());
    LOG.info("[LookupPreFilterRule] Right scan: {}", rightScan.getTable().getQualifiedName());

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
        "[LookupPreFilterRule] Estimated lookup cardinality: {} (threshold: {})",
        rightRowCount,
        MAX_TERMS_FOR_OPTIMIZATION);
    LOG.info(
        "[LookupPreFilterRule] Applying optimization: join keys: left={}, right={}",
        leftJoinKeyField,
        rightJoinKeyField);

    // Extract and push down filters that apply to the right side
    CalciteLogicalIndexScan rightScanWithFilters =
        extractAndPushRightSideFilters(filter, project, rightScan, leftFieldCount);

    // Pre-execute the right scan (with filters) to extract distinct join key values
    List<Object> joinKeyValues;
    try {
      joinKeyValues = preExecuteAndExtractJoinKeys(rightScanWithFilters, rightJoinKeyField);
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

    // Get field type and convert text fields to keyword for terms query
    OpenSearchIndex leftOsIndex = leftScan.getOsIndex();
    ExprType leftFieldType = leftOsIndex.getFieldOpenSearchTypes().get(leftJoinKeyField);
    String termsQueryField =
        OpenSearchTextType.convertTextToKeyword(leftJoinKeyField, leftFieldType);

    LOG.info(
        "[LookupPreFilterRule] Field type for {}: {}, using field: {} for terms query",
        leftJoinKeyField,
        leftFieldType,
        termsQueryField);

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
                      QueryBuilders.termsQuery(termsQueryField, joinKeyValues));
                  LOG.debug(
                      "Pushed down terms filter for field: {} with {} values",
                      termsQueryField,
                      joinKeyValues.size());
                });

    // Create new join with optimized left scan
    Join newJoin =
        join.copy(
            join.getTraitSet(),
            join.getCondition(),
            newLeftScan,
            rightScanWithFilters, // Use the scan with pushed filters
            join.getJoinType(),
            join.isSemiJoinDone());

    // Reconstruct the tree: Filter -> Project -> newJoin
    org.apache.calcite.rel.logical.LogicalProject newProject =
        (org.apache.calcite.rel.logical.LogicalProject)
            project.copy(
                project.getTraitSet(), newJoin, project.getProjects(), project.getRowType());

    org.apache.calcite.rel.logical.LogicalFilter newFilter =
        (org.apache.calcite.rel.logical.LogicalFilter)
            filter.copy(filter.getTraitSet(), newProject, filter.getCondition());

    LOG.info("[LookupPreFilterRule] Transformation applied, returning optimized plan");
    call.transformTo(newFilter);
  }

  /**
   * Extract filters from LogicalFilter that apply to right-side columns and push them to the right
   * scan.
   *
   * @param filter the LogicalFilter above the join
   * @param project the LogicalProject between filter and join
   * @param rightScan the right side scan
   * @param leftFieldCount number of fields from left side
   * @return rightScan with additional filters pushed down
   */
  private CalciteLogicalIndexScan extractAndPushRightSideFilters(
      org.apache.calcite.rel.logical.LogicalFilter filter,
      org.apache.calcite.rel.logical.LogicalProject project,
      CalciteLogicalIndexScan rightScan,
      int leftFieldCount) {

    LOG.info("[LookupPreFilterRule] Extracting right-side filters from LogicalFilter above join");

    // Create a copy of the right scan to modify
    CalciteLogicalIndexScan newRightScan = rightScan.copy();

    // The filter references project output fields
    // Project maps join output fields to its output
    // We need to trace which filter conditions apply to right-side join fields
    RexNode filterCondition = filter.getCondition();

    // Map filter condition through the project to get it in terms of join output fields
    java.util.List<RexNode> projectExprs = project.getProjects();
    org.apache.calcite.rex.RexBuilder rexBuilder = rightScan.getCluster().getRexBuilder();

    // For each conjunction in the filter, map it back to join space
    java.util.List<RexNode> conjunctions = new java.util.ArrayList<>();
    if (filterCondition.getKind() == org.apache.calcite.sql.SqlKind.AND) {
      for (RexNode operand : ((RexCall) filterCondition).getOperands()) {
        conjunctions.add(operand);
      }
    } else {
      conjunctions.add(filterCondition);
    }

    // Map each filter condition back through the project
    java.util.List<RexNode> rightSideFilters = new java.util.ArrayList<>();
    for (RexNode conj : conjunctions) {
      try {
        // Replace references to project outputs with the corresponding project expressions
        RexNode mappedToJoin =
            conj.accept(
                new org.apache.calcite.rex.RexShuttle() {
                  @Override
                  public RexNode visitInputRef(org.apache.calcite.rex.RexInputRef inputRef) {
                    int index = inputRef.getIndex();
                    if (index < projectExprs.size()) {
                      return projectExprs.get(index);
                    }
                    return inputRef;
                  }
                });

        // Check if this condition only references right-side fields
        java.util.Set<Integer> referencedFields = new java.util.HashSet<>();
        mappedToJoin.accept(
            new org.apache.calcite.rex.RexVisitorImpl<Void>(true) {
              @Override
              public Void visitInputRef(org.apache.calcite.rex.RexInputRef inputRef) {
                referencedFields.add(inputRef.getIndex());
                return null;
              }
            });

        // If all referenced fields are from right side (>= leftFieldCount), include this filter
        if (!referencedFields.isEmpty()
            && referencedFields.stream().allMatch(idx -> idx >= leftFieldCount)) {
          // Shift indices to be relative to right scan
          RexNode shifted = org.apache.calcite.rex.RexUtil.shift(mappedToJoin, -leftFieldCount);
          rightSideFilters.add(shifted);
          LOG.info("[LookupPreFilterRule] Extracted right-side filter: {}", shifted);
        }
      } catch (Exception e) {
        LOG.warn("[LookupPreFilterRule] Failed to map filter condition: {}", e.getMessage());
      }
    }

    // Push the right-side filters to the scan
    if (!rightSideFilters.isEmpty()) {
      RexNode combinedFilter =
          org.apache.calcite.rex.RexUtil.composeConjunction(rexBuilder, rightSideFilters);
      LOG.info("[LookupPreFilterRule] Pushing combined filter to right scan: {}", combinedFilter);

      try {
        // Analyze the filter using PredicateAnalyzer
        java.util.List<String> schema =
            new java.util.ArrayList<>(newRightScan.getRowType().getFieldNames());
        java.util.Map<String, org.opensearch.sql.data.type.ExprType> fieldTypes =
            newRightScan.getOsIndex().getAllFieldTypes();
        org.opensearch.sql.opensearch.request.PredicateAnalyzer.QueryExpression queryExpression =
            org.opensearch.sql.opensearch.request.PredicateAnalyzer.analyzeExpression(
                combinedFilter,
                schema,
                fieldTypes,
                newRightScan.getRowType(),
                rightScan.getCluster());

        // Add to push down context
        newRightScan
            .getPushDownContext()
            .add(
                queryExpression.getScriptCount() > 0 ? PushDownType.SCRIPT : PushDownType.FILTER,
                new org.opensearch.sql.opensearch.storage.scan.context.FilterDigest(
                    queryExpression.getScriptCount(), combinedFilter),
                (OSRequestBuilderAction)
                    requestBuilder ->
                        requestBuilder.pushDownFilterForCalcite(queryExpression.builder()));

        LOG.info(
            "[LookupPreFilterRule] Successfully pushed filters: {}",
            newRightScan.getPushDownContext());
      } catch (Exception e) {
        LOG.warn("[LookupPreFilterRule] Failed to push filter: {}", e.getMessage());
      }
    } else {
      LOG.info("[LookupPreFilterRule] No right-side-only filters found to push");
    }

    return newRightScan;
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
    LOG.info("[LookupPreFilterRule] Pre-executing lookup scan to extract join keys");
    LOG.info("[LookupPreFilterRule] Join key field: {}", joinKeyField);
    LOG.info("[LookupPreFilterRule] Right scan row type: {}", rightScan.getRowType());

    // Get the OpenSearch index and create request builder
    OpenSearchIndex osIndex = rightScan.getOsIndex();
    PushDownContext pushDownContext = rightScan.getPushDownContext();

    LOG.info("[LookupPreFilterRule] PushDownContext operations: {}", pushDownContext);

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
    int rowCount = 0;
    try {
      while (enumerator.moveNext()) {
        Object keyValue = enumerator.current();
        rowCount++;

        LOG.debug("[LookupPreFilterRule] Row {}: key value = {}", rowCount, keyValue);

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

    LOG.info(
        "[LookupPreFilterRule] Pre-execution complete: {} rows processed, {} distinct keys found",
        rowCount,
        distinctKeys.size());
    LOG.info("[LookupPreFilterRule] Distinct keys: {}", distinctKeys);

    return new ArrayList<>(distinctKeys);
  }
}
