/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.plugin.lookup.poc.transport;

import org.opensearch.action.ActionType;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.sql.plugin.lookup.poc.LookupStoragePoc;
import org.opensearch.sql.plugin.lookup.poc.model.StoreLookupRequest;
import org.opensearch.sql.plugin.lookup.poc.model.StoreLookupResponse;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

/**
 * Transport action for storing lookup data. Follows the 3-step process: 1. Check registry for
 * existing version 2. Write data with lookup_name + version tags 3. Update registry with new
 * version (using seq_no optimistic locking)
 */
public class TransportStoreLookupAction
    extends HandledTransportAction<StoreLookupRequest, StoreLookupResponse> {

  public static final String NAME = "cluster:admin/opensearch/ppl/lookup/store";
  public static final ActionType<StoreLookupResponse> ACTION_TYPE =
      new ActionType<>(NAME, StoreLookupResponse::new);

  private final LookupStoragePoc lookupStorage;

  @Inject
  public TransportStoreLookupAction(
      TransportService transportService,
      ActionFilters actionFilters,
      LookupStoragePoc lookupStorage) {
    super(NAME, transportService, actionFilters, StoreLookupRequest::new);
    this.lookupStorage = lookupStorage;
  }

  @Override
  protected void doExecute(
      Task task, StoreLookupRequest request, ActionListener<StoreLookupResponse> listener) {

    String lookupName = request.getLookupName();

    // For POC: Simple owner = "poc_user"
    // TODO: Get actual user from security context when integrating FGAC
    String owner = "poc_user";

    // Store lookup using centralized storage methods
    lookupStorage.storeLookup(
        lookupName,
        request.getData(),
        owner,
        ActionListener.wrap(
            result ->
                listener.onResponse(
                    new StoreLookupResponse(
                        result.getLookupName(), result.getVersion(), result.getRowCount())),
            listener::onFailure));
  }
}
