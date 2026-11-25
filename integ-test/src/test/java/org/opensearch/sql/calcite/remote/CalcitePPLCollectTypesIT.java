/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.sql.ppl.PPLIntegTestCase;

/**
 * End-to-end value-shape tests for {@code collect}: what is actually written to {@code _source}.
 */
public class CalcitePPLCollectTypesIT extends PPLIntegTestCase {

  private static final String MAP_AB =
      "{\"mappings\":{\"properties\":{\"a\":{\"type\":\"keyword\"},\"b\":{\"type\":\"integer\"}}}}";

  @Override
  public void init() throws Exception {
    super.init();
    enableCalcite();
  }

  private void put(String path, String body) throws IOException {
    Request req = new Request("PUT", path);
    req.setJsonEntity(body);
    client().performRequest(req);
  }

  private JSONObject source(String index) throws IOException {
    client().performRequest(new Request("POST", "/" + index + "/_refresh"));
    Response r = client().performRequest(new Request("GET", "/" + index + "/_search"));
    JSONObject body =
        new JSONObject(
            new String(r.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8));
    JSONArray hits = body.getJSONObject("hits").getJSONArray("hits");
    assertEquals(1, hits.length());
    return hits.getJSONObject(0).getJSONObject("_source");
  }

  /** Review bug 2: an object field is written once, as the object, not also as dotted keys. */
  @Test
  public void testObjectFieldIsWrittenOnceAndNestedTimestampIsIso() throws IOException {
    String mapping =
        "{\"mappings\":{\"properties\":{\"o\":{\"properties\":{\"a\":{\"type\":\"keyword\"},"
            + "\"t\":{\"type\":\"date\"}}}}}}";
    put("/collect_t_obj_src", mapping);
    put("/collect_t_obj_dst", mapping);
    put(
        "/collect_t_obj_src/_doc/1?refresh=true",
        "{\"o\":{\"a\":\"x\",\"t\":\"2026-11-23T10:00:00Z\"}}");
    executeQuery("source=collect_t_obj_src | collect index=collect_t_obj_dst");
    JSONObject src = source("collect_t_obj_dst");
    assertEquals("x", src.getJSONObject("o").getString("a"));
    assertEquals(2, src.length() + src.getJSONObject("o").length() - 1);
    assertTrue(src.toString(), !src.has("o.a"));
    assertTrue(
        src.getJSONObject("o").getString("t"),
        src.getJSONObject("o").getString("t").startsWith("2026-11-23T10:00:00"));
  }

  /**
   * Review bug A: projecting the object alone ({@code fields h}) left no dotted column carrying the
   * timestamp's type, so it was written as {@code yyyy-MM-dd HH:mm:ss} and a date destination
   * answered 400.
   */
  @Test
  public void testProjectedStructTimestampIsIso() throws IOException {
    String mapping =
        "{\"mappings\":{\"properties\":{\"h\":{\"properties\":{\"auth\":{\"type\":\"keyword\"},"
            + "\"ts\":{\"type\":\"date\"}}},\"k\":{\"type\":\"keyword\"}}}}";
    put("/collect_t_proj_src", mapping);
    put("/collect_t_proj_dst", mapping);
    put(
        "/collect_t_proj_src/_doc/1?refresh=true",
        "{\"h\":{\"auth\":\"x\",\"ts\":\"2023-11-14T22:13:20Z\"},\"k\":\"k1\"}");
    executeQuery("source=collect_t_proj_src | fields h | collect index=collect_t_proj_dst");
    JSONObject h = source("collect_t_proj_dst").getJSONObject("h");
    assertEquals("x", h.getString("auth"));
    assertEquals("2023-11-14T22:13:20Z", h.getString("ts"));
  }

  /** Review bug 2: copying an index with a nested field works. */
  @Test
  public void testNestedFieldCanBeCopied() throws IOException {
    String mapping =
        "{\"mappings\":{\"properties\":{\"n\":{\"type\":\"nested\",\"properties\":{"
            + "\"a\":{\"type\":\"keyword\"},\"t\":{\"type\":\"date\"}}}}}}";
    put("/collect_t_nest_src", mapping);
    put("/collect_t_nest_dst", mapping);
    put(
        "/collect_t_nest_src/_doc/1?refresh=true",
        "{\"n\":[{\"a\":\"x\",\"t\":\"2026-11-23T10:00:00Z\"}]}");
    executeQuery("source=collect_t_nest_src | collect index=collect_t_nest_dst");
    JSONObject n = source("collect_t_nest_dst").getJSONArray("n").getJSONObject(0);
    assertEquals("x", n.getString("a"));
  }

  /** Review bug 5: intervals carry their unit. */
  @Test
  public void testIntervalsAreWrittenAsIsoDurations() throws IOException {
    put("/collect_t_iv_src/_doc/1?refresh=true", "{\"a\":1}");
    put(
        "/collect_t_iv_dst",
        "{\"mappings\":{\"properties\":{\"m\":{\"type\":\"keyword\"},\"d\":{\"type\":\"keyword\"},\"y\":{\"type\":\"keyword\"}}}}");
    executeQuery(
        "source=collect_t_iv_src | eval m = INTERVAL 5 MINUTE, d = INTERVAL 2 DAY, y = INTERVAL 1"
            + " MONTH | fields m, d, y | collect index=collect_t_iv_dst");
    JSONObject src = source("collect_t_iv_dst");
    assertEquals("PT5M", src.getString("m"));
    assertEquals("P2D", src.getString("d"));
    assertEquals("P1M", src.getString("y"));
  }

  /** Review bug 6: rows whose id field is null fail the query instead of collapsing. */
  @Test
  public void testAllNullIdFailsLoudly() throws IOException {
    put("/collect_t_id_src", MAP_AB);
    put("/collect_t_id_src/_doc/1?refresh=true", "{\"b\":1}");
    put(
        "/collect_t_id_dst",
        "{\"mappings\":{\"properties\":{\"a\":{\"type\":\"keyword\"},\"b\":{\"type\":\"integer\"}}}}");
    Class<? extends Exception> expected =
        isStandaloneTest() ? RuntimeException.class : ResponseException.class;
    Exception e =
        assertThrows(
            expected,
            () ->
                executeQuery(
                    "source=collect_t_id_src | fields a, b |"
                        + " collect index=collect_t_id_dst id=a"));
    assertTrue(e.getMessage(), e.getMessage().contains("are all null"));
  }

  /** Review bug 11: a single-column input whose value is a struct is written as that column. */
  @Test
  public void testSingleStructColumnIsWrittenAsTheColumn() throws IOException {
    String mapping =
        "{\"mappings\":{\"properties\":{\"o\":{\"properties\":{\"a\":{\"type\":\"keyword\"},"
            + "\"b\":{\"type\":\"integer\"}}}}}}";
    put("/collect_t_one_src", mapping);
    put("/collect_t_one_dst", mapping);
    put("/collect_t_one_src/_doc/1?refresh=true", "{\"o\":{\"a\":\"x\",\"b\":2}}");
    executeQuery("source=collect_t_one_src | fields o | collect index=collect_t_one_dst");
    JSONObject o = source("collect_t_one_dst").getJSONObject("o");
    assertEquals("x", o.getString("a"));
    assertEquals(2, o.getInt("b"));
  }
}
