/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.remote;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.opensearch.sql.legacy.TestsConstants.TEST_INDEX_WORKER;
import static org.opensearch.sql.util.MatcherUtils.rows;
import static org.opensearch.sql.util.MatcherUtils.verifyDataRows;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.sql.ppl.PPLIntegTestCase;

/**
 * POC integration test demonstrating stored lookup detection in visitLookup().
 *
 * <p>This test verifies that the integration hook point in CalciteRelNodeVisitor.visitLookup()
 * correctly detects stored lookup references using the __stored__ prefix convention.
 *
 * <p>Full implementation would: 1. Query .sql_lookups_poc registry for version 2. Build filtered
 * relation to data index 3. Execute lookup with stored data
 *
 * <p>For POC, we verify the hook point throws UnsupportedOperationException with descriptive
 * message showing integration point is reached.
 */
public class CalcitePPLStoredLookupPocIT extends PPLIntegTestCase {

  @Override
  public void init() throws Exception {
    super.init();
    enableCalcite();
    loadIndex(Index.WORKER);
  }

  /**
   * Tests end-to-end stored lookup query - stores a lookup then queries it.
   *
   * <p>This validates the full flow: 1. Store lookup data via REST API 2. Query using LOOKUP with
   * __stored__ prefix 3. Verify results are returned from stored lookup
   */
  @Test
  public void testEndToEndStoredLookupQuery() throws IOException {
    String lookupName = "user_roles_" + System.currentTimeMillis();

    // Step 1: Store lookup data via REST API
    String storeLookupJson =
        "{\"data\": ["
            + "{\"id\": 1000, \"role\": \"admin\", \"department\": \"IT\"},"
            + "{\"id\": 1001, \"role\": \"user\", \"department\": \"Sales\"}"
            + "]}";

    Request storeRequest = new Request("POST", "/_plugins/_ppl/_poc/lookup/" + lookupName);
    storeRequest.setJsonEntity(storeLookupJson);

    Response storeResponse = client().performRequest(storeRequest);
    assertThat(storeResponse.getStatusLine().getStatusCode(), equalTo(200));

    // Wait for data to be indexed and refresh
    Request refreshRequest = new Request("POST", "/.sql_lookups_poc/_refresh");
    client().performRequest(refreshRequest);

    // Step 2: Query using stored lookup with __stored__ prefix
    // Note: The query will use the __stored__ prefix which triggers our POC logic
    JSONObject result =
        executeQuery(
            String.format(
                "source = %s | LOOKUP __stored__%s id REPLACE department | fields id, name,"
                    + " department",
                TEST_INDEX_WORKER, lookupName));

    // Step 3: Verify results
    // LOOKUP does left outer join - returns all source rows
    // Worker index has 6 rows: ids 1000-1005
    // Our stored lookup has: 1000 -> IT, 1001 -> Sales
    // Only rows with matching IDs get department from lookup
    verifyDataRows(
        result,
        rows(1000, "Jake", "IT"), // Matched lookup: gets IT
        rows(1001, "Hello", "Sales"), // Matched lookup: gets Sales
        rows(1002, "John", null), // No match: department remains null
        rows(1003, "David", null), // No match: department remains null
        rows(1004, "David", null), // No match: department remains null
        rows(1005, "Jane", null)); // No match: department remains null
  }
}
