/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.security;

import static org.opensearch.sql.util.MatcherUtils.verifyErrorMessageContains;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import org.opensearch.client.Request;
import org.opensearch.client.ResponseException;
import org.opensearch.sql.util.ClusterPlugins;

/**
 * {@code collect} writes with a bulk request that runs as the calling user. A user who can read the
 * source but not write the destination must get an error, not a 200 with zero rows written.
 */
public class CollectPermissionsIT extends SecurityTestBase {

  private static final String SRC = "collect_perm_src";
  private static final String DEST = "collect_perm_dest";
  private static final String WRITER = "collect_writer";
  private static final String READER = "collect_reader";

  private static final String[] WRITE_PERMISSIONS = {
    "indices:data/write/bulk*", "indices:data/write/index", "indices:admin/mapping/put"
  };

  private static boolean initialized = false;

  @Override
  protected void init() throws Exception {
    ClusterPlugins.requirePluginOrAssume(
        client(),
        ClusterPlugins.SECURITY_PLUGIN,
        "opensearch-security plugin not installed on test cluster; skipping FGAC tests");
    super.init();
    enableCalcite();
    if (!initialized) {
      createIndexWithMapping(
          SRC, "{\"mappings\":{\"properties\":{\"id\":{\"type\":\"integer\"}}}}");
      createIndexWithMapping(
          DEST, "{\"mappings\":{\"properties\":{\"id\":{\"type\":\"integer\"}}}}");
      for (int i = 1; i <= 3; i++) {
        Request doc = new Request("PUT", "/" + SRC + "/_doc/" + i + "?refresh=true");
        doc.setJsonEntity("{\"id\":" + i + "}");
        client().performRequest(doc);
      }
      String[] all = new String[PPL_INDEX_PERMISSIONS.length + WRITE_PERMISSIONS.length];
      System.arraycopy(PPL_INDEX_PERMISSIONS, 0, all, 0, PPL_INDEX_PERMISSIONS.length);
      System.arraycopy(
          WRITE_PERMISSIONS, 0, all, PPL_INDEX_PERMISSIONS.length, WRITE_PERMISSIONS.length);
      createRoleWithPermissions(
          "collect_writer_role",
          "collect_perm_*",
          new String[] {"cluster:admin/opensearch/ppl", "indices:data/write/bulk"},
          all);
      createUser(WRITER, "collect_writer_role");
      createRoleWithPermissions(
          "collect_reader_role",
          "collect_perm_*",
          new String[] {"cluster:admin/opensearch/ppl", "indices:data/write/bulk"},
          PPL_INDEX_PERMISSIONS);
      createUser(READER, "collect_reader_role");
      initialized = true;
    }
  }

  private long destCount() throws IOException {
    client().performRequest(new Request("POST", "/" + DEST + "/_refresh"));
    return new JSONObject(
            org.opensearch.sql.legacy.TestUtils.getResponseBody(
                client().performRequest(new Request("GET", "/" + DEST + "/_count")), true))
        .getLong("count");
  }

  @Test
  public void testCollectWritesAsUserWithWritePermission() throws IOException {
    long before = destCount();
    JSONObject result =
        executeQueryAsUser("source=" + SRC + " | fields id | collect index=" + DEST, WRITER);
    org.junit.Assert.assertEquals(3, result.getJSONArray("datarows").getJSONArray(0).getLong(0));
    org.junit.Assert.assertEquals(before + 3, destCount());
  }

  @Test
  public void testCollectFailsClosedWithoutWritePermission() throws IOException {
    long before = destCount();
    ResponseException e =
        org.junit.Assert.assertThrows(
            ResponseException.class,
            () ->
                executeQueryAsUser(
                    "source=" + SRC + " | fields id | collect index=" + DEST, READER));
    org.junit.Assert.assertEquals(403, e.getResponse().getStatusLine().getStatusCode());
    verifyErrorMessageContains(e, "rows_written=0");
    String body = org.opensearch.sql.legacy.TestUtils.getResponseBody(e.getResponse(), true);
    org.junit.Assert.assertFalse(body, body.contains("java.sql.SQLException"));
    org.junit.Assert.assertFalse(
        body, body.replaceAll("\\s", "").contains("\"type\":\"RuntimeException\""));
    org.junit.Assert.assertEquals(before, destCount());
  }
}
