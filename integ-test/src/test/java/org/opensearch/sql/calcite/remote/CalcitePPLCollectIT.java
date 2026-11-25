/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.opensearch.sql.util.MatcherUtils.verifyErrorMessageContains;

import java.io.IOException;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.opensearch.client.Request;
import org.opensearch.client.ResponseException;
import org.opensearch.sql.ppl.PPLIntegTestCase;

/**
 * End-to-end tests for the PPL {@code collect} command: rows are written to a pre-existing
 * destination, the result is one {@code (rows_written, rows_failed)} row, any rejected row fails
 * the query, and {@code id=} makes re-runs idempotent.
 */
public class CalcitePPLCollectIT extends PPLIntegTestCase {

  private static final String MAPPING =
      "{\"mappings\":{\"properties\":{\"id\":{\"type\":\"integer\"},"
          + "\"name\":{\"type\":\"keyword\"},\"status\":{\"type\":\"integer\"}}}}";

  @Override
  public void init() throws Exception {
    super.init();
    enableCalcite();
  }

  private void createIndex(String index, String body) throws IOException {
    Request req = new Request("PUT", "/" + index);
    req.setJsonEntity(body);
    client().performRequest(req);
  }

  private void indexDoc(String index, int id, String json) throws IOException {
    Request req = new Request("PUT", "/" + index + "/_doc/" + id + "?refresh=true");
    req.setJsonEntity(json);
    client().performRequest(req);
  }

  private void seed(String src) throws IOException {
    createIndex(src, MAPPING);
    indexDoc(src, 1, "{\"id\":1,\"name\":\"a\",\"status\":200}");
    indexDoc(src, 2, "{\"id\":2,\"name\":\"b\",\"status\":500}");
    indexDoc(src, 3, "{\"id\":3,\"name\":\"c\",\"status\":503}");
  }

  private long count(String index) throws IOException {
    client().performRequest(new Request("POST", "/" + index + "/_refresh"));
    Request req = new Request("GET", "/" + index + "/_count");
    JSONObject body =
        new JSONObject(
            new String(
                client().performRequest(req).getEntity().getContent().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
    return body.getLong("count");
  }

  private static long rowsWritten(JSONObject result) {
    JSONArray rows = result.getJSONArray("datarows");
    assertEquals(1, rows.length());
    return rows.getJSONArray(0).getLong(0);
  }

  private Exception assertQueryFails(String ppl) {
    Class<? extends Exception> expected =
        isStandaloneTest() ? RuntimeException.class : ResponseException.class;
    return assertThrows(expected, () -> executeQuery(ppl));
  }

  @Test
  public void testCollectWritesAndReturnsCounts() throws IOException {
    seed("collect_src_small");
    createIndex("collect_dest_small", MAPPING);
    JSONObject result =
        executeQuery(
            "source=collect_src_small | fields id, name | collect index=collect_dest_small");
    assertEquals("rows_written", result.getJSONArray("schema").getJSONObject(0).getString("name"));
    assertEquals(3L, rowsWritten(result));
    assertEquals(3L, count("collect_dest_small"));
  }

  /** Would have caught T2: a second run doubled the destination (16 then 32). */
  @Test
  public void testCollectWithIdIsIdempotentOnRerun() throws IOException {
    seed("collect_src_rerun");
    createIndex("collect_dest_rerun", MAPPING);
    String ppl =
        "source=collect_src_rerun | stats count() as id by name | collect index=collect_dest_rerun"
            + " id=name";
    executeQuery(ppl);
    long first = count("collect_dest_rerun");
    executeQuery(ppl);
    assertEquals(3L, first);
    assertEquals(
        "re-running the same collect must not change _count", first, count("collect_dest_rerun"));
  }

  /** Would have caught T5: rows rejected by the dest mapping returned HTTP 200. */
  @Test
  public void testCollectFailsWhenDestinationRejectsRows() throws IOException {
    seed("collect_src_reject");
    createIndex(
        "collect_dest_reject", "{\"mappings\":{\"properties\":{\"name\":{\"type\":\"long\"}}}}");
    Exception e =
        assertQueryFails(
            "source=collect_src_reject | fields name | collect index=collect_dest_reject");
    verifyErrorMessageContains(e, "rows_failed=3");
    verifyErrorMessageContains(e, "MapperParsingException");
  }

  /**
   * Would have caught T6 (200 with 0 written) and review bug D (testmode passed for a write-blocked
   * destination): both the write and the dry run are refused at analysis, with 403 and no write.
   */
  @Test
  public void testCollectFailsOnWriteBlockedDestination() throws IOException {
    seed("collect_src_block");
    for (String block :
        new String[] {
          "index.blocks.write", "index.blocks.read_only", "index.blocks.read_only_allow_delete"
        }) {
      String dest = "collect_dest_block_" + block.substring(block.lastIndexOf('.') + 1);
      createIndex(dest, "{\"settings\":{\"" + block + "\":true}," + MAPPING.substring(1));
      try {
        for (String suffix : new String[] {"", " testmode=true"}) {
          Exception e =
              assertQueryFails(
                  "source=collect_src_block | fields id | collect index=" + dest + suffix);
          verifyErrorMessageContains(e, "[" + dest + "]");
          verifyErrorMessageContains(e, block);
          assertEquals(403, status(e));
        }
      } finally {
        Request unblock = new Request("PUT", "/" + dest + "/_settings");
        unblock.setJsonEntity("{\"" + block + "\":false}");
        client().performRequest(unblock);
      }
      assertEquals(0L, count(dest));
    }
  }

  /** Would have caught T1: timestamps were written as 'yyyy-MM-dd HH:mm:ss'. */
  @Test
  public void testCollectTimestampIntoDateDestination() throws IOException {
    createIndex("collect_src_ts", "{\"mappings\":{\"properties\":{\"ts\":{\"type\":\"date\"}}}}");
    indexDoc("collect_src_ts", 1, "{\"ts\":\"2026-11-23T10:00:00Z\"}");
    createIndex("collect_dest_ts", "{\"mappings\":{\"properties\":{\"ts\":{\"type\":\"date\"}}}}");
    assertEquals(
        1L,
        rowsWritten(
            executeQuery("source=collect_src_ts | fields ts | collect index=collect_dest_ts")));
    assertEquals(1L, count("collect_dest_ts"));
  }

  /** Would have caught T3: ip and geo_point columns failed with HTTP 500. */
  @Test
  public void testCollectIpAndGeoPoint() throws IOException {
    String mapping =
        "{\"mappings\":{\"properties\":{\"ip\":{\"type\":\"ip\"},\"loc\":{\"type\":\"geo_point\"}}}}";
    createIndex("collect_src_geo", mapping);
    indexDoc("collect_src_geo", 1, "{\"ip\":\"10.0.0.1\",\"loc\":{\"lat\":37.7,\"lon\":-122.4}}");
    createIndex("collect_dest_geo", mapping);
    assertEquals(
        1L,
        rowsWritten(
            executeQuery(
                "source=collect_src_geo | fields ip, loc | collect index=collect_dest_geo")));
    assertEquals(1L, count("collect_dest_geo"));
  }

  @Test
  public void testCollectStreamsPastResultWindow() throws IOException {
    String src = "collect_src_big";
    createIndex(src, MAPPING);
    createIndex("collect_dest_big", MAPPING);
    int total = 10500; // > default max_result_window and plugins.query.size_limit
    StringBuilder bulk = new StringBuilder();
    for (int i = 1; i <= total; i++) {
      bulk.append(
          String.format(Locale.ROOT, "{\"index\":{\"_index\":\"%s\",\"_id\":%d}}%n", src, i));
      bulk.append(String.format(Locale.ROOT, "{\"id\":%d,\"status\":200}%n", i));
      if (i % 2000 == 0 || i == total) {
        Request req = new Request("POST", "/_bulk?refresh=true");
        req.setJsonEntity(bulk.toString());
        client().performRequest(req);
        bulk.setLength(0);
      }
    }
    assertEquals(
        (long) total,
        rowsWritten(
            executeQuery("source=collect_src_big | fields id | collect index=collect_dest_big")));
    assertEquals((long) total, count("collect_dest_big"));
  }

  @Test
  public void testCollectMissingDestinationFails() throws IOException {
    seed("collect_src_missing");
    verifyErrorMessageContains(
        assertQueryFails("source=collect_src_missing | collect index=collect_no_such_dest"),
        "collect_no_such_dest");
  }

  @Test
  public void testCollectDotIndexRefused() throws IOException {
    seed("collect_src_dot");
    verifyErrorMessageContains(
        assertQueryFails("source=collect_src_dot | collect index=.system_dest"),
        "system or hidden");
  }

  @Test
  public void testCollectTestmodeDoesNotWrite() throws IOException {
    seed("collect_src_testmode");
    createIndex("collect_dest_testmode", MAPPING);
    JSONObject result =
        executeQuery(
            "source=collect_src_testmode | fields id | collect index=collect_dest_testmode"
                + " testmode=true");
    assertEquals(3, result.getJSONArray("datarows").length());
    assertEquals(0L, count("collect_dest_testmode"));
  }

  private JSONObject get(String path) throws IOException {
    return new JSONObject(
        new String(
            client()
                .performRequest(new Request("GET", path))
                .getEntity()
                .getContent()
                .readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8));
  }

  /** The _source of every doc in the index, after a refresh. */
  private JSONArray sources(String index) throws IOException {
    client().performRequest(new Request("POST", "/" + index + "/_refresh"));
    JSONArray hits =
        get("/" + index + "/_search?size=100").getJSONObject("hits").getJSONArray("hits");
    JSONArray out = new JSONArray();
    for (int i = 0; i < hits.length(); i++) {
      out.put(hits.getJSONObject(i).getJSONObject("_source"));
    }
    return out;
  }

  private static int status(Exception e) {
    return ((ResponseException) e).getResponse().getStatusLine().getStatusCode();
  }

  private void setPushdown(String value) throws IOException {
    updateClusterSettings(
        new ClusterSetting("transient", "plugins.calcite.pushdown.enabled", value));
  }

  /**
   * Bug 1: rename/eval after a pushed-down projection wrote the source names (`name`, not `who`),
   * and id=who failed at runtime. Runs with pushdown on and off.
   */
  @Test
  public void testCollectWritesRenamedColumnsUnderOutputNames() throws IOException {
    seed("collect_src_rename");
    for (String pushdown : new String[] {"true", "false"}) {
      setPushdown(pushdown);
      try {
        String dest = "collect_dest_rename_" + pushdown;
        createIndex(dest, "{}");
        executeQuery(
            "source=collect_src_rename | stats count() as c by name | rename name as who"
                + " | collect index="
                + dest
                + " id=who");
        executeQuery(
            "source=collect_src_rename | eval y = id | fields y, name | rename name as who2"
                + " | collect index="
                + dest);
        JSONArray docs = sources(dest);
        assertEquals("pushdown=" + pushdown + " " + docs, 6, docs.length());
        for (int i = 0; i < docs.length(); i++) {
          JSONObject doc = docs.getJSONObject(i);
          assertFalse("pushdown=" + pushdown + " wrote a source name: " + doc, doc.has("name"));
          assertFalse("pushdown=" + pushdown + " wrote a source name: " + doc, doc.has("id"));
          assertTrue(doc.toString(), doc.has("who") || (doc.has("who2") && doc.has("y")));
        }
      } finally {
        setPushdown("true");
      }
    }
  }

  /** Bug 4: an alias, index.hidden=true and date math must not bypass the hidden-index guard. */
  @Test
  public void testCollectHiddenIndexGuardResolvesTheName() throws IOException {
    seed("collect_src_hidden");
    createIndex(".collect_hidden_target", MAPPING);
    Request alias = new Request("POST", "/_aliases");
    alias.setJsonEntity(
        "{\"actions\":[{\"add\":{\"index\":\".collect_hidden_target\","
            + "\"alias\":\"collect_plain_alias\"}}]}");
    client().performRequest(alias);
    createIndex(
        "collect_hidden_nodot", "{\"settings\":{\"index.hidden\":true}," + MAPPING.substring(1));
    client().performRequest(new Request("PUT", "/%3C.collect_dm-%7Bnow%2Fd%7D%3E"));
    for (String dest :
        new String[] {"collect_plain_alias", "collect_hidden_nodot", "`<.collect_dm-{now/d}>`"}) {
      Exception e =
          assertQueryFails("source=collect_src_hidden | fields id | collect index=" + dest);
      verifyErrorMessageContains(e, "system or hidden");
      assertEquals(dest, 400, status(e));
    }
    assertEquals(0L, count(".collect_hidden_target"));
    assertEquals(0L, count("collect_hidden_nodot"));
  }

  /** Bug 4: wildcards, comma lists and _all are refused at analysis with a 400. */
  @Test
  public void testCollectMultiIndexDestinationRefused() throws IOException {
    seed("collect_src_multi");
    for (String dest : new String[] {"collect_dest_m*", "collect_a, collect_b", "_all"}) {
      Exception e = assertQueryFails("source=collect_src_multi | collect index=" + dest);
      verifyErrorMessageContains(e, "must be a single index or alias");
      verifyErrorMessageContains(e, "SemanticCheckException");
      assertEquals(dest, 400, status(e));
    }
  }

  /** Bug 8: a dry run fails where the write would (missing destination, bad id field). */
  @Test
  public void testCollectTestmodeValidatesLikeAWrite() throws IOException {
    seed("collect_src_tm_valid");
    createIndex("collect_dest_tm_valid", MAPPING);
    verifyErrorMessageContains(
        assertQueryFails(
            "source=collect_src_tm_valid | collect index=collect_tm_no_such testmode=true"),
        "does not exist");
    verifyErrorMessageContains(
        assertQueryFails(
            "source=collect_src_tm_valid | fields name | collect index=collect_dest_tm_valid"
                + " id=status testmode=true"),
        "is not a column of the input");
    // Review E: the list names the columns the user can pick, not the internal metadata columns.
    Exception e =
        assertQueryFails(
            "source=collect_src_tm_valid | collect index=collect_dest_tm_valid id=nope"
                + " testmode=true");
    verifyErrorMessageContains(e, "is not a column of the input");
    String body = e.getMessage();
    for (String internal :
        new String[] {"_id", "_index", "_score", "_maxscore", "_sort", "_routing"}) {
      assertFalse(body, body.contains(internal + ","));
      assertFalse(body, body.contains(internal + "]"));
    }
  }

  /** Bug 8: the preview drops all-null rows, which the write skips. */
  @Test
  public void testCollectTestmodeOmitsAllNullRows() throws IOException {
    seed("collect_src_tm_null");
    indexDoc("collect_src_tm_null", 4, "{\"status\":404}");
    createIndex("collect_dest_tm_null", MAPPING);
    String ppl =
        "source=collect_src_tm_null | fields id, name | collect index=collect_dest_tm_null";
    JSONObject preview = executeQuery(ppl + " testmode=true");
    assertEquals(3, preview.getJSONArray("datarows").length());
    assertEquals(3L, rowsWritten(executeQuery(ppl)));
  }

  /** Bug 9: a missing destination gets the friendly analysis message, as a 400. */
  @Test
  public void testCollectMissingDestinationIsFriendly400() throws IOException {
    seed("collect_src_missing400");
    Exception e =
        assertQueryFails("source=collect_src_missing400 | collect index=collect_no_such_dest2");
    verifyErrorMessageContains(
        e, "collect destination index [collect_no_such_dest2] does not exist");
    assertEquals(400, status(e));
  }

  /** Bug 9: a destination that is not an index (a system catalog table) gets a clear error. */
  @Test
  public void testCollectNonIndexDestinationRefused() throws IOException {
    seed("collect_src_nonindex");
    Exception e =
        assertQueryFails("source=collect_src_nonindex | collect index=`ALL.META_ODFE_SYS_TABLE`");
    verifyErrorMessageContains(e, "is not an OpenSearch index");
    assertEquals(400, status(e));
  }

  /** Bug 13: a duplicate testmode used to be accepted, last value winning, so it wrote. */
  @Test
  public void testCollectDuplicateOptionRefused() throws IOException {
    seed("collect_src_dup");
    createIndex("collect_dest_dup", MAPPING);
    Exception e =
        assertQueryFails(
            "source=collect_src_dup | fields id | collect index=collect_dest_dup testmode=true"
                + " testmode=false");
    verifyErrorMessageContains(e, "more than once");
    assertEquals(0L, count("collect_dest_dup"));
  }

  /** plugins.ppl.write_commands.enable=false refuses collect (testmode too) and writes nothing. */
  @Test
  public void testCollectRefusedWhenWriteCommandsDisabled() throws IOException {
    seed("collect_src_disabled");
    createIndex("collect_dest_disabled", MAPPING);
    String ppl = "source=collect_src_disabled | fields id | collect index=collect_dest_disabled";
    updateClusterSettings(
        new ClusterSetting("transient", "plugins.ppl.write_commands.enable", "false"));
    try {
      for (String q : new String[] {ppl, ppl + " testmode=true"}) {
        Exception e = assertQueryFails(q);
        verifyErrorMessageContains(e, "plugins.ppl.write_commands.enable");
        assertEquals(400, status(e));
        // The text is in `reason`, not only `details`, so a client that shows only reason shows it.
        String reason =
            new JSONObject(
                    org.opensearch.sql.util.TestUtils.getResponseBody(
                        ((ResponseException) e).getResponse(), false))
                .getJSONObject("error")
                .getString("reason");
        assertTrue(reason, reason.contains("plugins.ppl.write_commands.enable"));
      }
      assertEquals(0L, count("collect_dest_disabled"));
    } finally {
      updateClusterSettings(
          new ClusterSetting("transient", "plugins.ppl.write_commands.enable", "true"));
    }
    assertEquals(3L, rowsWritten(executeQuery(ppl)));
  }

  /**
   * Review bug B: a null id after earlier batches (1000 rows each) fails the query, and the error
   * says how many rows were written before it.
   */
  @Test
  public void testCollectNullIdInALaterBatchReportsRowsWritten() throws IOException {
    createIndex(
        "collect_src_nullid",
        "{\"mappings\":{\"properties\":{\"g\":{\"type\":\"keyword\"},"
            + "\"n\":{\"type\":\"integer\"}}}}");
    createIndex(
        "collect_dest_nullid",
        "{\"mappings\":{\"properties\":{\"g\":{\"type\":\"keyword\"},"
            + "\"n\":{\"type\":\"integer\"}}}}");
    StringBuilder bulk = new StringBuilder();
    for (int i = 0; i < 2500; i++) {
      bulk.append("{\"index\":{}}\n");
      bulk.append(i < 2200 ? "{\"g\":\"g" + i + "\",\"n\":" + i + "}\n" : "{\"n\":" + i + "}\n");
    }
    Request req = new Request("POST", "/collect_src_nullid/_bulk?refresh=true");
    req.setJsonEntity(bulk.toString());
    client().performRequest(req);
    Exception e =
        assertQueryFails(
            "source=collect_src_nullid | fields g, n | sort n | collect index=collect_dest_nullid"
                + " id=g");
    verifyErrorMessageContains(e, "all null");
    verifyErrorMessageContains(e, "rows_written=2000");
    assertEquals(400, status(e));
    assertEquals(2000L, count("collect_dest_nullid"));
  }
}
