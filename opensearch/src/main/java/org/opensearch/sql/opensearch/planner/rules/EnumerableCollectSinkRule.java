/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.planner.rules;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.opensearch.sql.calcite.plan.rel.LogicalCollectSink;
import org.opensearch.sql.opensearch.storage.OpenSearchIndex;
import org.opensearch.sql.opensearch.storage.scan.EnumerableCollectSink;

/**
 * Converts a {@link LogicalCollectSink} whose target is an {@link OpenSearchIndex} into the
 * physical {@link EnumerableCollectSink}.
 */
public class EnumerableCollectSinkRule extends ConverterRule {

  public static final Config DEFAULT_CONFIG =
      Config.INSTANCE
          .as(Config.class)
          .withConversion(
              LogicalCollectSink.class,
              Convention.NONE,
              EnumerableConvention.INSTANCE,
              "EnumerableCollectSinkRule")
          .withRuleFactory(EnumerableCollectSinkRule::new);

  protected EnumerableCollectSinkRule(Config config) {
    super(config);
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    LogicalCollectSink collect = call.rel(0);
    return collect.getTable().unwrap(OpenSearchIndex.class) != null;
  }

  @Override
  public RelNode convert(RelNode rel) {
    final LogicalCollectSink collect = (LogicalCollectSink) rel;
    RelTraitSet traitSet = collect.getTraitSet().replace(EnumerableConvention.INSTANCE);
    RelNode convertedInput =
        convert(
            collect.getInput(),
            collect.getInput().getTraitSet().replace(EnumerableConvention.INSTANCE));
    return new EnumerableCollectSink(
        collect.getCluster(),
        traitSet,
        convertedInput,
        collect.getTable(),
        collect.getIdFields(),
        collect.getFieldNames());
  }
}
