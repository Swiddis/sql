/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.planner.rules;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.apache.calcite.adapter.enumerable.EnumerableRules;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.junit.jupiter.api.Test;

class EnumerableCollectSinkRuleTest {

  /**
   * Calcite's own EnumerableCollectRule (MULTISET Collect) is registered by default. A rule with
   * the same description fails every collect query with "Rule's description should be unique".
   */
  @Test
  void ruleCoexistsWithCalciteDefaultEnumerableRules() {
    VolcanoPlanner planner = new VolcanoPlanner();
    EnumerableRules.ENUMERABLE_RULES.forEach(planner::addRule);
    assertDoesNotThrow(
        () -> OpenSearchIndexRules.OPEN_SEARCH_NON_PUSHDOWN_RULES.forEach(planner::addRule));
  }
}
