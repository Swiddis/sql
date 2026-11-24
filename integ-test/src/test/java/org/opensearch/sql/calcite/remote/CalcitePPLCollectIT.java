/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
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

  /** Would have caught T6: a write-blocked dest returned HTTP 200 with 0 written. */
  @Test
  public void testCollectFailsOnWriteBlockedDestination() throws IOException {
    seed("collect_src_block");
    createIndex(
        "collect_dest_block",
        "{\"settings\":{\"index.blocks.write\":true}," + MAPPING.substring(1));
    try {
      assertQueryFails("source=collect_src_block | fields id | collect index=collect_dest_block");
    } finally {
      Request unblock = new Request("PUT", "/collect_dest_block/_settings");
      unblock.setJsonEntity("{\"index.blocks.write\":false}");
      client().performRequest(unblock);
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
}
