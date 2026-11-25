/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.sql.legacy.TestUtils;
import org.opensearch.sql.util.ClusterPlugins;

/**
 * {@code collect} reads the source as the calling user, so document- and field-level security on
 * the source decide what reaches the destination. A user who sees a subset of the source must write
 * only that subset, and a field hidden by FLS must not appear in the written documents.
 */
public class CollectDlsFlsIT extends SecurityTestBase {

  private static final String SRC = "collect_sec_src";
  private static final String DLS_DEST = "collect_sec_dls_dest";
  private static final String FLS_DEST = "collect_sec_fls_dest";
  private static final String DLS_USER = "collect_dls_user";
  private static final String FLS_USER = "collect_fls_user";

  private static final String SRC_PERMS =
      "\"indices:data/read/search*\", \"indices:admin/mappings/get\","
          + " \"indices:monitor/settings/get\", \"indices:data/read/point_in_time/create\","
          + " \"indices:data/read/point_in_time/delete\", \"indices:admin/resolve/index\","
          + " \"indices:data/read/field_caps*\"";

  private static final String DEST_PERMS =
      SRC_PERMS
          + ", \"indices:data/write/bulk*\", \"indices:data/write/index\","
          + " \"indices:admin/mapping/put\"";

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
      String mapping =
          "{\"mappings\":{\"properties\":{\"id\":{\"type\":\"integer\"},"
              + "\"dept\":{\"type\":\"keyword\"},\"secret\":{\"type\":\"keyword\"}}}}";
      createIndexWithMapping(SRC, mapping);
      createIndexWithMapping(DLS_DEST, mapping);
      createIndexWithMapping(FLS_DEST, mapping);
      String[] depts = {"eng", "eng", "sales", "sales", "sales"};
      for (int i = 0; i < depts.length; i++) {
        Request doc = new Request("PUT", "/" + SRC + "/_doc/" + (i + 1) + "?refresh=true");
        doc.setJsonEntity(
            String.format(
                Locale.ROOT,
                "{\"id\":%d,\"dept\":\"%s\",\"secret\":\"s%d\"}",
                i + 1,
                depts[i],
                i + 1));
        client().performRequest(doc);
      }
      // DLS: only dept=eng on the source; plain write on the destination.
      putRole(
          "collect_dls_role",
          String.format(
              Locale.ROOT,
              "{\"index_patterns\":[\"%s\"],\"allowed_actions\":[%s],"
                  + "\"dls\":\"{\\\"term\\\":{\\\"dept\\\":\\\"eng\\\"}}\"},"
                  + "{\"index_patterns\":[\"%s\"],\"allowed_actions\":[%s]}",
              SRC,
              SRC_PERMS,
              DLS_DEST,
              DEST_PERMS));
      createUser(DLS_USER, "collect_dls_role");
      // FLS: the source hides `secret`; plain write on the destination.
      putRole(
          "collect_fls_role",
          String.format(
              Locale.ROOT,
              "{\"index_patterns\":[\"%s\"],\"allowed_actions\":[%s],"
                  + "\"fls\":[\"id\",\"dept\"]},"
                  + "{\"index_patterns\":[\"%s\"],\"allowed_actions\":[%s]}",
              SRC,
              SRC_PERMS,
              FLS_DEST,
              DEST_PERMS));
      createUser(FLS_USER, "collect_fls_role");
      initialized = true;
    }
  }

  private void putRole(String name, String indexPermissions) throws IOException {
    Request role = new Request("PUT", "/_plugins/_security/api/roles/" + name);
    role.setJsonEntity(
        "{\"cluster_permissions\":[\"cluster:admin/opensearch/ppl\","
            + " \"indices:data/write/bulk\"],\"index_permissions\":["
            + indexPermissions
            + "]}");
    Response response = client().performRequest(role);
    int code = response.getStatusLine().getStatusCode();
    assertTrue("role " + name + " returned " + code, code == 200 || code == 201);
  }

  /** Reads every document of an index as the admin client (no DLS/FLS). */
  private JSONArray adminHits(String index) throws IOException {
    client().performRequest(new Request("POST", "/" + index + "/_refresh"));
    Request search = new Request("GET", "/" + index + "/_search?size=100");
    return new JSONObject(TestUtils.getResponseBody(client().performRequest(search), true))
        .getJSONObject("hits")
        .getJSONArray("hits");
  }

  @Test
  public void testCollectWritesOnlyDocumentsVisibleUnderDls() throws IOException {
    assertEquals("admin sees the full source", 5, adminHits(SRC).length());

    JSONObject result =
        executeQueryAsUser(
            "source=" + SRC + " | fields id, dept | collect index=" + DLS_DEST, DLS_USER);

    assertEquals(2, result.getJSONArray("datarows").getJSONArray(0).getLong(0));
    JSONArray hits = adminHits(DLS_DEST);
    Set<String> depts = new TreeSet<>();
    Set<Integer> ids = new TreeSet<>();
    for (int i = 0; i < hits.length(); i++) {
      JSONObject source = hits.getJSONObject(i).getJSONObject("_source");
      depts.add(source.getString("dept"));
      ids.add(source.getInt("id"));
    }
    assertEquals(Set.of("eng"), depts);
    assertEquals(Set.of(1, 2), ids);
  }

  @Test
  public void testCollectOmitsFieldHiddenByFls() throws IOException {
    JSONObject result =
        executeQueryAsUser("source=" + SRC + " | collect index=" + FLS_DEST, FLS_USER);

    assertEquals(5, result.getJSONArray("datarows").getJSONArray(0).getLong(0));
    JSONArray hits = adminHits(FLS_DEST);
    assertEquals(5, hits.length());
    for (int i = 0; i < hits.length(); i++) {
      JSONObject source = hits.getJSONObject(i).getJSONObject("_source");
      assertTrue(source.has("id"));
      assertFalse("FLS-hidden field leaked: " + source, source.has("secret"));
    }
  }
}
