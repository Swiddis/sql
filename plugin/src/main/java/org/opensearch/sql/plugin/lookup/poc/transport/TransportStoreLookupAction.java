/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.plugin.lookup.poc.transport;

import org.opensearch.action.ActionType;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.util.concurrent.ThreadContext;
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

  private static final String OPENDISTRO_SECURITY_USER_KEY = "_opendistro_security_user";
  private static final String POC_DEFAULT_USER = "poc_user";

  private final LookupStoragePoc lookupStorage;
  private final TransportService transportService;

  @Inject
  public TransportStoreLookupAction(
      TransportService transportService,
      ActionFilters actionFilters,
      LookupStoragePoc lookupStorage) {
    super(NAME, transportService, actionFilters, StoreLookupRequest::new);
    this.lookupStorage = lookupStorage;
    this.transportService = transportService;
  }

  /**
   * Extracts the username from security context. Returns a default user if security plugin is not
   * installed (fail-open mode).
   */
  private String extractUsername() {
    ThreadContext threadContext = transportService.getThreadPool().getThreadContext();
    Object userObj = threadContext.getTransient(OPENDISTRO_SECURITY_USER_KEY);

    if (userObj == null) {
      // Fail-open mode: security plugin not installed
      return POC_DEFAULT_USER;
    }

    try {
      // Use reflection to get username from User object (security plugin may not be available at
      // compile time)
      return (String) userObj.getClass().getMethod("getName").invoke(userObj);
    } catch (Exception e) {
      // Fallback to toString or default if reflection fails
      return POC_DEFAULT_USER;
    }
  }

  @Override
  protected void doExecute(
      Task task, StoreLookupRequest request, ActionListener<StoreLookupResponse> listener) {

    String lookupName = request.getLookupName();

    // Extract authenticated user from security context
    // Falls back to POC_DEFAULT_USER if security plugin is not installed
    String owner = extractUsername();

    // Store lookup using centralized storage methods
    lookupStorage.storeLookup(
        lookupName,
        request.getSchema(),
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
