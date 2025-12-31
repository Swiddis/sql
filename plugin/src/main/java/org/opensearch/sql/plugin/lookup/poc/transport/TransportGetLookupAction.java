/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.plugin.lookup.poc.transport;

import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.ActionType;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.sql.plugin.lookup.poc.LookupStoragePoc;
import org.opensearch.sql.plugin.lookup.poc.model.GetLookupRequest;
import org.opensearch.sql.plugin.lookup.poc.model.GetLookupResponse;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

/**
 * Transport action for getting lookup metadata from registry. Used internally for query resolution.
 * ALWAYS checks registry before allowing data access.
 */
public class TransportGetLookupAction
    extends HandledTransportAction<GetLookupRequest, GetLookupResponse> {

  public static final String NAME = "cluster:admin/opensearch/ppl/lookup/get";
  public static final ActionType<GetLookupResponse> ACTION_TYPE =
      new ActionType<>(NAME, GetLookupResponse::new);

  private final LookupStoragePoc lookupStorage;

  @Inject
  public TransportGetLookupAction(
      TransportService transportService,
      ActionFilters actionFilters,
      LookupStoragePoc lookupStorage) {
    super(NAME, transportService, actionFilters, GetLookupRequest::new);
    this.lookupStorage = lookupStorage;
  }

  @Override
  protected void doExecute(
      Task task, GetLookupRequest request, ActionListener<GetLookupResponse> listener) {

    String lookupName = request.getLookupName();

    // SECURITY CRITICAL: Always check registry first
    // This ensures we only return data for lookups that exist in the registry
    lookupStorage.getFromRegistry(
        lookupName,
        ActionListener.wrap(
            getResponse -> {
              if (!getResponse.isExists()) {
                listener.onFailure(
                    new OpenSearchStatusException(
                        "Lookup not found: " + lookupName, RestStatus.NOT_FOUND));
                return;
              }

              // Extract version from registry
              String version = (String) getResponse.getSourceAsMap().get("version");

              // Return lookup metadata (will be used to build data filter)
              listener.onResponse(
                  new GetLookupResponse(lookupName, version, LookupStoragePoc.DATA_INDEX_NAME));
            },
            listener::onFailure));
  }
}
