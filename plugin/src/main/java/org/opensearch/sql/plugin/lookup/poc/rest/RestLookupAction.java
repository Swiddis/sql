/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.plugin.lookup.poc.rest;

import static org.opensearch.rest.RestRequest.Method.GET;
import static org.opensearch.rest.RestRequest.Method.POST;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.BytesRestResponse;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;
import org.opensearch.sql.plugin.lookup.poc.model.GetLookupRequest;
import org.opensearch.sql.plugin.lookup.poc.model.StoreLookupRequest;
import org.opensearch.sql.plugin.lookup.poc.transport.TransportGetLookupAction;
import org.opensearch.sql.plugin.lookup.poc.transport.TransportStoreLookupAction;
import org.opensearch.transport.client.node.NodeClient;

/**
 * REST handler for lookup operations (POC). Supports: - POST
 * /_plugins/_ppl/_poc/lookup/{lookup_name} - Store lookup data - GET
 * /_plugins/_ppl/_poc/lookup/{lookup_name} - Get lookup metadata
 */
public class RestLookupAction extends BaseRestHandler {

  public static final String LOOKUP_ACTIONS = "lookup_actions_poc";
  public static final String BASE_LOOKUP_ACTION_URL = "/_plugins/_ppl/_poc/lookup";

  @Override
  public String getName() {
    return LOOKUP_ACTIONS;
  }

  @Override
  public List<Route> routes() {
    return ImmutableList.of(
        // POST /_plugins/_ppl/_poc/lookup/{lookup_name} - Store lookup data
        new Route(
            POST, String.format(Locale.ROOT, "%s/{%s}", BASE_LOOKUP_ACTION_URL, "lookup_name")),
        // GET /_plugins/_ppl/_poc/lookup/{lookup_name} - Get lookup metadata
        new Route(
            GET, String.format(Locale.ROOT, "%s/{%s}", BASE_LOOKUP_ACTION_URL, "lookup_name")));
  }

  @Override
  protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient nodeClient)
      throws IOException {
    switch (restRequest.method()) {
      case POST:
        return executeStoreLookup(restRequest, nodeClient);
      case GET:
        return executeGetLookup(restRequest, nodeClient);
      default:
        return restChannel ->
            restChannel.sendResponse(
                new BytesRestResponse(
                    RestStatus.METHOD_NOT_ALLOWED, String.valueOf(restRequest.method())));
    }
  }

  /**
   * Handle POST request to store lookup data. Request: POST
   * /_plugins/_ppl/_poc/lookup/{lookup_name} Body: { "data": [ {...}, {...} ] }
   */
  private RestChannelConsumer executeStoreLookup(RestRequest restRequest, NodeClient nodeClient)
      throws IOException {
    String lookupName = restRequest.param("lookup_name");

    if (lookupName == null || lookupName.trim().isEmpty()) {
      return restChannel ->
          restChannel.sendResponse(
              new BytesRestResponse(
                  RestStatus.BAD_REQUEST, "lookup_name path parameter is required"));
    }

    // Parse request body
    XContentParser parser = restRequest.contentParser();
    Map<String, Object> requestMap = parser.map();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> data = (List<Map<String, Object>>) requestMap.get("data");

    if (data == null || data.isEmpty()) {
      return restChannel ->
          restChannel.sendResponse(
              new BytesRestResponse(
                  RestStatus.BAD_REQUEST, "data field is required and cannot be empty"));
    }

    StoreLookupRequest request = new StoreLookupRequest(lookupName, data);

    return restChannel ->
        nodeClient.execute(
            TransportStoreLookupAction.ACTION_TYPE,
            request,
            new RestToXContentListener<>(restChannel));
  }

  /**
   * Handle GET request to get lookup metadata. Request: GET
   * /_plugins/_ppl/_poc/lookup/{lookup_name}
   */
  private RestChannelConsumer executeGetLookup(RestRequest restRequest, NodeClient nodeClient) {
    String lookupName = restRequest.param("lookup_name");

    if (lookupName == null || lookupName.trim().isEmpty()) {
      return restChannel ->
          restChannel.sendResponse(
              new BytesRestResponse(
                  RestStatus.BAD_REQUEST, "lookup_name path parameter is required"));
    }

    GetLookupRequest request = new GetLookupRequest(lookupName);

    return restChannel ->
        nodeClient.execute(
            TransportGetLookupAction.ACTION_TYPE,
            request,
            new RestToXContentListener<>(restChannel));
  }
}
