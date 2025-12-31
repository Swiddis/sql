/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.opensearch.sql.legacy.TestUtils.getResponseBody;

import java.io.IOException;
import java.util.Locale;
import lombok.SneakyThrows;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.opensearch.client.Request;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;

/**
 * Integration tests for Lookup Storage security (Step 5.5).
 *
 * <p>Tests owner-based access control: - Non-admin users can only access lookups they created -
 * Tests verify security is enforced at both store and get operations
 *
 * <p>NOTE: Admin permission model is TBD. The "all_access" role does NOT automatically grant access
 * to custom cluster actions (cluster:admin/opensearch/ppl/lookup/*). Admin tests have been removed
 * pending design decision on admin permission model.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class LookupStorageSecurityIT extends SecurityTestBase {
  private static final String ALICE_USER = "alice";
  private static final String ALICE_ROLE = "alice_role";
  private static final String BOB_USER = "bob";
  private static final String BOB_ROLE = "bob_role";

  private static final String LOOKUP_BASE_URL = "/_plugins/_ppl/_poc/lookup";

  @SneakyThrows
  @BeforeAll
  public void initialize() {
    setUpIndices();
    createSecurityRolesAndUsers();
  }

  @Override
  protected void init() throws Exception {
    super.init();
  }

  /** Creates security roles and users for testing. */
  private void createSecurityRolesAndUsers() throws IOException {
    // Role for alice: can use lookup API
    createRoleWithPermissions(
        ALICE_ROLE,
        "*", // Allow access to all indices (lookup storage handles its own access control)
        new String[] {
          "cluster:admin/opensearch/ppl",
          "cluster:admin/opensearch/ppl/lookup/store",
          "cluster:admin/opensearch/ppl/lookup/get"
        },
        new String[] {
          "indices:data/read/search*",
          "indices:data/read/get",
          "indices:data/write/index",
          "indices:data/write/bulk",
          "indices:admin/mappings/get",
          "indices:monitor/settings/get"
        });
    createUser(ALICE_USER, ALICE_ROLE);

    // Role for bob: can use lookup API
    createRoleWithPermissions(
        BOB_ROLE,
        "*",
        new String[] {
          "cluster:admin/opensearch/ppl",
          "cluster:admin/opensearch/ppl/lookup/store",
          "cluster:admin/opensearch/ppl/lookup/get"
        },
        new String[] {
          "indices:data/read/search*",
          "indices:data/read/get",
          "indices:data/write/index",
          "indices:data/write/bulk",
          "indices:admin/mappings/get",
          "indices:monitor/settings/get"
        });
    createUser(BOB_USER, BOB_ROLE);
  }

  // ==================== Store Lookup Tests ====================

  @Test
  public void testAliceCanStoreHerOwnLookup() throws IOException {
    String lookupName = "alice_lookup_" + System.currentTimeMillis();

    JSONObject requestBody = new JSONObject();
    JSONArray data = new JSONArray();
    JSONObject row = new JSONObject();
    row.put("id", "1");
    row.put("name", "Alice's Data");
    data.put(row);
    requestBody.put("data", data);

    Request request =
        new Request("POST", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, lookupName));
    request.setJsonEntity(requestBody.toString());
    setJsonContentType(request);
    addBasicAuth(request, ALICE_USER);

    Response response = client().performRequest(request);

    assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
    JSONObject result = new JSONObject(getResponseBody(response, true));
    assertThat(result.getString("lookup_name"), equalTo(lookupName));
    assertThat(result.has("version"), equalTo(true));
    assertThat(result.getInt("row_count"), equalTo(1));
  }

  @Test
  public void testBobCanStoreHisOwnLookup() throws IOException {
    String lookupName = "bob_lookup_" + System.currentTimeMillis();

    JSONObject requestBody = new JSONObject();
    JSONArray data = new JSONArray();
    JSONObject row = new JSONObject();
    row.put("id", "2");
    row.put("name", "Bob's Data");
    data.put(row);
    requestBody.put("data", data);

    Request request =
        new Request("POST", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, lookupName));
    request.setJsonEntity(requestBody.toString());
    setJsonContentType(request);
    addBasicAuth(request, BOB_USER);

    Response response = client().performRequest(request);

    assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
    JSONObject result = new JSONObject(getResponseBody(response, true));
    assertThat(result.getString("lookup_name"), equalTo(lookupName));
  }

  // NOTE: Admin tests removed - admin permission model is TBD
  // The "all_access" role does NOT automatically grant access to custom cluster actions

  // ==================== Get Lookup Tests (Access Control) ====================

  @Test
  public void testAliceCanGetHerOwnLookup() throws IOException {
    // First, Alice creates a lookup
    String lookupName = "alice_own_lookup_" + System.currentTimeMillis();
    storeLookupAsUser(lookupName, "Alice's data", ALICE_USER);

    // Then, Alice retrieves it
    Request request =
        new Request("GET", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, lookupName));
    setJsonContentType(request);
    addBasicAuth(request, ALICE_USER);

    Response response = client().performRequest(request);

    assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
    JSONObject result = new JSONObject(getResponseBody(response, true));
    assertThat(result.getString("lookup_name"), equalTo(lookupName));
    assertThat(result.has("version"), equalTo(true));
  }

  @Test
  public void testBobCannotGetAlicesLookup() throws IOException {
    // Alice creates a lookup
    String lookupName = "alice_private_lookup_" + System.currentTimeMillis();
    storeLookupAsUser(lookupName, "Alice's private data", ALICE_USER);

    // Bob tries to retrieve it (should fail with 403 FORBIDDEN)
    Request request =
        new Request("GET", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, lookupName));
    setJsonContentType(request);
    addBasicAuth(request, BOB_USER);

    try {
      client().performRequest(request);
      fail("Expected ResponseException for Bob accessing Alice's lookup");
    } catch (ResponseException e) {
      assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(403));
      String body = getResponseBody(e.getResponse());
      assertThat(body, containsString("Access denied"));
      assertThat(body, containsString(BOB_USER));
    }
  }

  @Test
  public void testAliceCannotGetBobsLookup() throws IOException {
    // Bob creates a lookup
    String lookupName = "bob_private_lookup_" + System.currentTimeMillis();
    storeLookupAsUser(lookupName, "Bob's private data", BOB_USER);

    // Alice tries to retrieve it (should fail with 403 FORBIDDEN)
    Request request =
        new Request("GET", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, lookupName));
    setJsonContentType(request);
    addBasicAuth(request, ALICE_USER);

    try {
      client().performRequest(request);
      fail("Expected ResponseException for Alice accessing Bob's lookup");
    } catch (ResponseException e) {
      assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(403));
      String body = getResponseBody(e.getResponse());
      assertThat(body, containsString("Access denied"));
      assertThat(body, containsString(ALICE_USER));
    }
  }

  // Admin access tests removed pending permission model design

  // ==================== Helper Methods ====================

  /** Helper method to store a lookup as a specific user */
  private void storeLookupAsUser(String lookupName, String dataValue, String username)
      throws IOException {
    JSONObject requestBody = new JSONObject();
    JSONArray data = new JSONArray();
    JSONObject row = new JSONObject();
    row.put("value", dataValue);
    data.put(row);
    requestBody.put("data", data);

    Request request =
        new Request("POST", String.format(Locale.ROOT, "%s/%s", LOOKUP_BASE_URL, lookupName));
    request.setJsonEntity(requestBody.toString());
    setJsonContentType(request);
    addBasicAuth(request, username);

    Response response = client().performRequest(request);
    assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
  }

  private void setJsonContentType(Request request) {
    RequestOptions.Builder restOptionsBuilder = RequestOptions.DEFAULT.toBuilder();
    restOptionsBuilder.addHeader("Content-Type", "application/json");
    request.setOptions(restOptionsBuilder);
  }

  private void addBasicAuth(Request request, String username) {
    RequestOptions.Builder restOptionsBuilder = RequestOptions.DEFAULT.toBuilder();
    restOptionsBuilder.addHeader("Content-Type", "application/json");
    restOptionsBuilder.addHeader("Authorization", createBasicAuthHeader(username, STRONG_PASSWORD));
    request.setOptions(restOptionsBuilder);
  }
}
