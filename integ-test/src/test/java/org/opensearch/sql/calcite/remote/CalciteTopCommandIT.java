/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.remote;

import org.opensearch.sql.ppl.TopCommandIT;

public class CalciteTopCommandIT extends TopCommandIT {
  @Override
  public void init() throws Exception {
    super.init();
    enableCalcite();
    // TODO: "https://github.com/opensearch-project/sql/issues/3464"
    // disallowCalciteFallback();
  }
}
