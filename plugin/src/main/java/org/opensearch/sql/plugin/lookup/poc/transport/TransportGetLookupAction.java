/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.plugin.lookup.poc.transport;

import java.util.Set;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.ActionType;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.util.concurrent.ThreadContext;
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

  private static final String OPENDISTRO_SECURITY_USER_KEY = "_opendistro_security_user";
  private static final String POC_DEFAULT_USER = "poc_user";
  private static final String ADMIN_ROLE = "all_access";

  private final LookupStoragePoc lookupStorage;
  private final TransportService transportService;

  @Inject
  public TransportGetLookupAction(
      TransportService transportService,
      ActionFilters actionFilters,
      LookupStoragePoc lookupStorage) {
    super(NAME, transportService, actionFilters, GetLookupRequest::new);
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
      // Use reflection to get username from User object
      return (String) userObj.getClass().getMethod("getName").invoke(userObj);
    } catch (Exception e) {
      return POC_DEFAULT_USER;
    }
  }

  /**
   * Checks if the current user has admin privileges (has "all_access" role). Admins can access all
   * lookups regardless of owner.
   */
  @SuppressWarnings("unchecked")
  private boolean isAdmin() {
    ThreadContext threadContext = transportService.getThreadPool().getThreadContext();
    Object userObj = threadContext.getTransient(OPENDISTRO_SECURITY_USER_KEY);

    if (userObj == null) {
      // Fail-open mode: treat as admin (backward compatible)
      return true;
    }

    try {
      // Use reflection to get roles from User object
      Set<String> roles = (Set<String>) userObj.getClass().getMethod("getRoles").invoke(userObj);
      return roles != null && roles.contains(ADMIN_ROLE);
    } catch (Exception e) {
      // If reflection fails, default to non-admin for safety
      return false;
    }
  }

  @Override
  protected void doExecute(
      Task task, GetLookupRequest request, ActionListener<GetLookupResponse> listener) {

    String lookupName = request.getLookupName();
    String currentUser = extractUsername();
    boolean isAdmin = isAdmin();

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

              // Extract owner and version from registry
              String owner = (String) getResponse.getSourceAsMap().get("owner");
              String version = (String) getResponse.getSourceAsMap().get("version");

              // SECURITY CHECK: Verify user has permission to access this lookup
              // - Admins (with "all_access" role) can access all lookups
              // - Non-admins can only access lookups they own
              if (!isAdmin && !currentUser.equals(owner)) {
                listener.onFailure(
                    new OpenSearchStatusException(
                        "Access denied: User '"
                            + currentUser
                            + "' does not have permission to access lookup '"
                            + lookupName
                            + "'",
                        RestStatus.FORBIDDEN));
                return;
              }

              // Return lookup metadata (will be used to build data filter)
              listener.onResponse(
                  new GetLookupResponse(lookupName, version, LookupStoragePoc.DATA_INDEX_NAME));
            },
            listener::onFailure));
  }
}
