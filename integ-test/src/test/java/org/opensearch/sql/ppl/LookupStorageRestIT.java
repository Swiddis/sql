/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.ppl;

import static org.hamcrest.Matchers.*;
import static org.opensearch.sql.legacy.TestUtils.getResponseBody;

import java.io.IOException;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.opensearch.client.Request;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;

/**
 * Integration tests for Lookup Storage REST API (POC). Tests the fail-open mode (no security plugin
 * installed).
 *
 * <p>Endpoints tested: - POST /_plugins/_ppl/_poc/lookup/{lookup_name} - GET
 * /_plugins/_ppl/_poc/lookup/{lookup_name}
 */
public class LookupStorageRestIT extends PPLIntegTestCase {

  private static final String LOOKUP_BASE_URL = "/_plugins/_ppl/_poc/lookup";

  // ==================== POST Tests (Store Lookup) ====================

  @Test
  public void testStoreLookup_Success() throws IOException {
    String lookupName = "test_lookup_" + System.currentTimeMillis();

    // Prepare test data
    JSONObject requestBody = new JSONObject();
    JSONArray data = new JSONArray();

    JSONObject row1 = new JSONObject();
    row1.put("user_id", "123");
    row1.put("user_name", "Alice");
    row1.put("department", "Engineering");
    data.put(row1);

    JSONObject row2 = new JSONObject();
    row2.put("user_id", "456");
    row2.put("user_name", "Bob");
    row2.put("department", "Sales");
    data.put(row2);

    requestBody.put("data", data);

    // Execute POST request
    Request request =
        new Request("POST", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, lookupName));
    request.setJsonEntity(requestBody.toString());
    setJsonContentType(request);

    Response response = client().performRequest(request);

    // Verify response
    assertThat(response.getStatusLine().getStatusCode(), equalTo(200));

    JSONObject result = new JSONObject(getResponseBody(response, true));
    assertThat(result.getString("lookup_name"), equalTo(lookupName));
    assertThat(result.has("version"), equalTo(true));
    assertThat(result.getString("version").length(), greaterThan(0));
    assertThat(result.getInt("row_count"), equalTo(2));
  }

  @Test
  public void testStoreLookup_UpdateExisting() throws IOException {
    String lookupName = "test_lookup_update_" + System.currentTimeMillis();

    // First insert
    JSONObject requestBody1 = new JSONObject();
    JSONArray data1 = new JSONArray();
    JSONObject row1 = new JSONObject();
    row1.put("id", "1");
    row1.put("value", "original");
    data1.put(row1);
    requestBody1.put("data", data1);

    Request request1 =
        new Request("POST", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, lookupName));
    request1.setJsonEntity(requestBody1.toString());
    setJsonContentType(request1);

    Response response1 = client().performRequest(request1);
    assertThat(response1.getStatusLine().getStatusCode(), equalTo(200));

    JSONObject result1 = new JSONObject(getResponseBody(response1, true));
    String version1 = result1.getString("version");

    // Second insert (update)
    JSONObject requestBody2 = new JSONObject();
    JSONArray data2 = new JSONArray();
    JSONObject row2 = new JSONObject();
    row2.put("id", "1");
    row2.put("value", "updated");
    data2.put(row2);
    requestBody2.put("data", data2);

    Request request2 =
        new Request("POST", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, lookupName));
    request2.setJsonEntity(requestBody2.toString());
    setJsonContentType(request2);

    Response response2 = client().performRequest(request2);
    assertThat(response2.getStatusLine().getStatusCode(), equalTo(200));

    JSONObject result2 = new JSONObject(getResponseBody(response2, true));
    String version2 = result2.getString("version");

    // Version should be different
    assertThat(version2, not(equalTo(version1)));
  }

  @Test
  public void testStoreLookup_MissingLookupName() throws IOException {
    // Missing lookup_name in path should return 400 BAD_REQUEST
    // (the route matches but validation fails)
    Request request = new Request("POST", LOOKUP_BASE_URL);
    request.setJsonEntity("{\"data\": [{\"id\": \"1\"}]}");
    setJsonContentType(request);

    try {
      client().performRequest(request);
      fail("Expected ResponseException for missing lookup_name");
    } catch (ResponseException e) {
      // Either 400 or 405 is acceptable depending on routing
      assertThat(
          e.getResponse().getStatusLine().getStatusCode(), anyOf(equalTo(400), equalTo(405)));
    }
  }

  @Test
  public void testStoreLookup_EmptyLookupName() throws IOException {
    Request request = new Request("POST", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, ""));
    request.setJsonEntity("{\"data\": [{\"id\": \"1\"}]}");
    setJsonContentType(request);

    try {
      client().performRequest(request);
      fail("Expected ResponseException for empty lookup_name");
    } catch (ResponseException e) {
      // Should return either 400 or 405 depending on routing
      assertThat(
          e.getResponse().getStatusLine().getStatusCode(), anyOf(equalTo(400), equalTo(405)));
    }
  }

  @Test
  public void testStoreLookup_MissingDataField() throws IOException {
    String lookupName = "test_lookup_missing_data_" + System.currentTimeMillis();

    Request request =
        new Request("POST", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, lookupName));
    request.setJsonEntity("{}");
    setJsonContentType(request);

    try {
      client().performRequest(request);
      fail("Expected ResponseException for missing data field");
    } catch (ResponseException e) {
      assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(400));
      String body = getResponseBody(e.getResponse());
      assertThat(body, containsString("data field is required"));
    }
  }

  @Test
  public void testStoreLookup_EmptyDataArray() throws IOException {
    String lookupName = "test_lookup_empty_data_" + System.currentTimeMillis();

    Request request =
        new Request("POST", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, lookupName));
    request.setJsonEntity("{\"data\": []}");
    setJsonContentType(request);

    try {
      client().performRequest(request);
      fail("Expected ResponseException for empty data array");
    } catch (ResponseException e) {
      assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(400));
      String body = getResponseBody(e.getResponse());
      assertThat(body, containsString("cannot be empty"));
    }
  }

  // ==================== GET Tests (Get Lookup Metadata) ====================

  @Test
  public void testGetLookup_Success() throws IOException {
    String lookupName = "test_lookup_get_" + System.currentTimeMillis();

    // First store a lookup
    JSONObject storeRequest = new JSONObject();
    JSONArray data = new JSONArray();
    JSONObject row = new JSONObject();
    row.put("id", "1");
    row.put("name", "test");
    data.put(row);
    storeRequest.put("data", data);

    Request storeReq =
        new Request("POST", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, lookupName));
    storeReq.setJsonEntity(storeRequest.toString());
    setJsonContentType(storeReq);

    Response storeResponse = client().performRequest(storeReq);
    assertThat(storeResponse.getStatusLine().getStatusCode(), equalTo(200));

    JSONObject storeResult = new JSONObject(getResponseBody(storeResponse, true));
    String expectedVersion = storeResult.getString("version");

    // Now GET the lookup metadata
    Request getReq =
        new Request("GET", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, lookupName));
    setJsonContentType(getReq);

    Response getResponse = client().performRequest(getReq);

    // Verify response
    assertThat(getResponse.getStatusLine().getStatusCode(), equalTo(200));

    JSONObject result = new JSONObject(getResponseBody(getResponse, true));
    assertThat(result.getString("lookup_name"), equalTo(lookupName));
    assertThat(result.getString("version"), equalTo(expectedVersion));
    assertThat(result.has("index"), equalTo(true));
  }

  @Test
  public void testGetLookup_NotFound() throws IOException {
    String lookupName = "nonexistent_lookup_" + System.currentTimeMillis();

    Request request =
        new Request("GET", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, lookupName));
    setJsonContentType(request);

    try {
      client().performRequest(request);
      fail("Expected ResponseException for nonexistent lookup");
    } catch (ResponseException e) {
      assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(404));
    }
  }

  @Test
  public void testGetLookup_MissingLookupName() throws IOException {
    Request request = new Request("GET", LOOKUP_BASE_URL);
    setJsonContentType(request);

    try {
      client().performRequest(request);
      fail("Expected ResponseException for missing lookup_name");
    } catch (ResponseException e) {
      // Either 400 or 405 is acceptable depending on routing
      assertThat(
          e.getResponse().getStatusLine().getStatusCode(), anyOf(equalTo(400), equalTo(405)));
    }
  }

  // ==================== Helper Methods ====================

  private void setJsonContentType(Request request) {
    RequestOptions.Builder restOptionsBuilder = RequestOptions.DEFAULT.toBuilder();
    restOptionsBuilder.addHeader("Content-Type", "application/json");
    request.setOptions(restOptionsBuilder);
  }
}
