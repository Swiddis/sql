/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.plugin.lookup.poc;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.indices.create.CreateIndexResponse;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.index.engine.VersionConflictEngineException;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.transport.client.node.NodeClient;

/**
 * Storage layer for Lookup POC. Manages two hidden indices: - .sql_lookup_registry_poc: Registry
 * mapping lookup names to UUIDs - .sql_lookups_poc: Actual lookup data tagged with UUIDs
 */
public class LookupStoragePoc {

  private static final Logger LOG = LogManager.getLogger(LookupStoragePoc.class);

  public static final String REGISTRY_INDEX_NAME = ".sql_lookup_registry_poc";
  public static final String DATA_INDEX_NAME = ".sql_lookups_poc";

  private final NodeClient client;
  private final ClusterService clusterService;
  private final AtomicBoolean initialized = new AtomicBoolean(false);

  public LookupStoragePoc(NodeClient client, ClusterService clusterService) {
    this.client = client;
    this.clusterService = clusterService;
  }

  /**
   * Initialize both registry and data indices. Should be called on plugin startup. This method is
   * idempotent.
   */
  public void initializeIndices() {
    if (initialized.compareAndSet(false, true)) {
      createRegistryIndex();
      createDataIndex();
    }
  }

  /**
   * Create the registry index with mapping for lookup metadata. Registry stores: lookup_name,
   * version (UUID), owner, updated_at
   */
  private void createRegistryIndex() {
    if (clusterService.state().metadata().hasIndex(REGISTRY_INDEX_NAME)) {
      LOG.info("Registry index {} already exists", REGISTRY_INDEX_NAME);
      return;
    }

    try {
      String mapping = getRegistryMapping();
      String settings = getRegistrySettings();

      CreateIndexRequest createIndexRequest = new CreateIndexRequest(REGISTRY_INDEX_NAME);
      createIndexRequest
          .mapping(mapping, MediaTypeRegistry.JSON)
          .settings(settings, MediaTypeRegistry.JSON);

      CreateIndexResponse createIndexResponse;
      try (ThreadContext.StoredContext ignored =
          client.threadPool().getThreadContext().stashContext()) {
        createIndexResponse = client.admin().indices().create(createIndexRequest).actionGet();
      }

      if (createIndexResponse.isAcknowledged()) {
        LOG.info("Registry index {} created successfully", REGISTRY_INDEX_NAME);
      } else {
        throw new RuntimeException("Registry index creation not acknowledged");
      }
    } catch (Throwable e) {
      throw new RuntimeException(
          "Failed to create registry index " + REGISTRY_INDEX_NAME + ": " + e.getMessage(), e);
    }
  }

  /**
   * Create the data index for storing lookup data. Data documents include: lookup_name, version
   * (UUID), and user fields
   */
  private void createDataIndex() {
    if (clusterService.state().metadata().hasIndex(DATA_INDEX_NAME)) {
      LOG.info("Data index {} already exists", DATA_INDEX_NAME);
      return;
    }

    try {
      String settings = getDataIndexSettings();
      String mapping = getDataIndexMapping();

      CreateIndexRequest createIndexRequest = new CreateIndexRequest(DATA_INDEX_NAME);
      createIndexRequest.settings(settings, MediaTypeRegistry.JSON);
      createIndexRequest.mapping(mapping, MediaTypeRegistry.JSON);

      CreateIndexResponse createIndexResponse;
      try (ThreadContext.StoredContext ignored =
          client.threadPool().getThreadContext().stashContext()) {
        createIndexResponse = client.admin().indices().create(createIndexRequest).actionGet();
      }

      if (createIndexResponse.isAcknowledged()) {
        LOG.info("Data index {} created successfully", DATA_INDEX_NAME);
      } else {
        throw new RuntimeException("Data index creation not acknowledged");
      }
    } catch (Throwable e) {
      throw new RuntimeException(
          "Failed to create data index " + DATA_INDEX_NAME + ": " + e.getMessage(), e);
    }
  }

  /**
   * Get mapping for registry index. Fields: lookup_name (keyword), version (keyword), owner
   * (keyword), updated_at (date)
   */
  private String getRegistryMapping() {
    return "{\n"
        + "  \"properties\": {\n"
        + "    \"lookup_name\": {\"type\": \"keyword\"},\n"
        + "    \"version\": {\"type\": \"keyword\"},\n"
        + "    \"owner\": {\"type\": \"keyword\"},\n"
        + "    \"updated_at\": {\"type\": \"date\"}\n"
        + "  }\n"
        + "}";
  }

  /** Get settings for registry index. Hidden index with 1 shard and 1 replica. */
  private String getRegistrySettings() {
    return "{\n"
        + "  \"index\": {\n"
        + "    \"hidden\": true,\n"
        + "    \"number_of_shards\": 1,\n"
        + "    \"number_of_replicas\": 1,\n"
        + "    \"auto_expand_replicas\": \"0-2\"\n"
        + "  }\n"
        + "}";
  }

  /**
   * Get settings for data index. Hidden index with dynamic mapping (user data has arbitrary
   * fields).
   */
  private String getDataIndexSettings() {
    return "{\n"
        + "  \"index\": {\n"
        + "    \"hidden\": true,\n"
        + "    \"number_of_shards\": 1,\n"
        + "    \"number_of_replicas\": 1,\n"
        + "    \"auto_expand_replicas\": \"0-2\"\n"
        + "  }\n"
        + "}";
  }

  /**
   * Get mapping for data index. Uses dynamic templates to map numeric fields as integer (not long)
   * for better compatibility with typical use cases. Required fields: lookup_name (keyword),
   * version (keyword).
   */
  private String getDataIndexMapping() {
    return "{\n"
        + "  \"dynamic_templates\": [\n"
        + "    {\n"
        + "      \"integers\": {\n"
        + "        \"match_mapping_type\": \"long\",\n"
        + "        \"mapping\": {\n"
        + "          \"type\": \"integer\"\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  ],\n"
        + "  \"properties\": {\n"
        + "    \"lookup_name\": {\"type\": \"keyword\"},\n"
        + "    \"version\": {\"type\": \"keyword\"}\n"
        + "  }\n"
        + "}";
  }

  /** Check if registry index exists */
  public boolean registryIndexExists() {
    return clusterService.state().metadata().hasIndex(REGISTRY_INDEX_NAME);
  }

  /** Check if data index exists */
  public boolean dataIndexExists() {
    return clusterService.state().metadata().hasIndex(DATA_INDEX_NAME);
  }

  // ==================== CENTRALIZED ACCESS CONTROL ====================
  // All data access MUST go through these methods to ensure proper filtering

  /**
   * CENTRALIZED FILTER BUILDER - SECURITY CRITICAL Builds filter for querying data index. Always
   * adds lookup_name and version filters. This is the ONLY place where data index filters are
   * constructed.
   *
   * @param lookupName The lookup name (always required)
   * @param version The version UUID (always required)
   * @return BoolQueryBuilder with both lookup_name and version filters
   */
  public BoolQueryBuilder buildDataFilter(String lookupName, String version) {
    if (lookupName == null || lookupName.trim().isEmpty()) {
      throw new IllegalArgumentException("lookup_name is required for data access");
    }
    if (version == null || version.trim().isEmpty()) {
      throw new IllegalArgumentException("version is required for data access");
    }

    // CRITICAL: Always filter by BOTH lookup_name AND version
    // This ensures queries only see data for the specific lookup version
    return QueryBuilders.boolQuery()
        .filter(QueryBuilders.termQuery("lookup_name", lookupName))
        .filter(QueryBuilders.termQuery("version", version));
  }

  /**
   * Get lookup metadata from registry. This MUST be called before any data index access.
   *
   * @param lookupName The lookup name
   * @param listener Async callback with GetResponse
   */
  public void getFromRegistry(String lookupName, ActionListener<GetResponse> listener) {
    GetRequest getRequest = new GetRequest(REGISTRY_INDEX_NAME, lookupName);
    try (ThreadContext.StoredContext ignored =
        client.threadPool().getThreadContext().stashContext()) {
      client.get(getRequest, listener);
    }
  }

  /**
   * Store lookup data. This is a 3-step process: 1. Check if lookup exists (for seq_no) 2. Write
   * data with lookup_name + version tags 3. Update registry with new version (using seq_no
   * optimistic locking)
   *
   * @param lookupName The lookup name
   * @param data The data rows to store
   * @param owner The owner (for POC, just use a simple string; enhance with FGAC later)
   * @param listener Async callback with version UUID and row count
   */
  public void storeLookup(
      String lookupName,
      List<Map<String, Object>> data,
      String owner,
      ActionListener<StoreLookupResult> listener) {

    // Generate UUID for this version
    String version = UUID.randomUUID().toString();

    // Step 1: Check if lookup exists in registry (need seq_no for optimistic locking)
    getFromRegistry(
        lookupName,
        ActionListener.wrap(
            getResponse -> {
              // Step 2: Write data with lookup_name + version tags
              writeData(
                  lookupName,
                  version,
                  data,
                  ActionListener.wrap(
                      rowCount -> {
                        // Step 3: Update registry with new version
                        updateRegistry(lookupName, version, owner, getResponse, rowCount, listener);
                      },
                      listener::onFailure));
            },
            listener::onFailure));
  }

  /**
   * Write data to data index with lookup_name and version tags. ALWAYS adds lookup_name and version
   * to every row.
   */
  private void writeData(
      String lookupName,
      String version,
      List<Map<String, Object>> data,
      ActionListener<Integer> listener) {

    BulkRequest bulkRequest = new BulkRequest();
    for (Map<String, Object> row : data) {
      // Create a copy to avoid modifying original data
      Map<String, Object> docWithMetadata = new HashMap<>(row);

      // CRITICAL: Tag every row with lookup_name and version
      docWithMetadata.put("lookup_name", lookupName);
      docWithMetadata.put("version", version);

      IndexRequest indexRequest = new IndexRequest(DATA_INDEX_NAME).source(docWithMetadata);
      bulkRequest.add(indexRequest);
    }

    try (ThreadContext.StoredContext ignored =
        client.threadPool().getThreadContext().stashContext()) {
      client.bulk(
          bulkRequest,
          ActionListener.wrap(
              bulkResponse -> {
                if (bulkResponse.hasFailures()) {
                  listener.onFailure(
                      new OpenSearchStatusException(
                          "Failed to write lookup data: " + bulkResponse.buildFailureMessage(),
                          RestStatus.INTERNAL_SERVER_ERROR));
                } else {
                  listener.onResponse(data.size());
                }
              },
              listener::onFailure));
    }
  }

  /**
   * Update registry with new version using seq_no optimistic locking. If concurrent update
   * detected, returns conflict error.
   */
  private void updateRegistry(
      String lookupName,
      String version,
      String owner,
      GetResponse existingDoc,
      int rowCount,
      ActionListener<StoreLookupResult> listener) {

    Map<String, Object> registry = new HashMap<>();
    registry.put("lookup_name", lookupName);
    registry.put("version", version);
    registry.put("owner", owner);
    registry.put("updated_at", Instant.now().toString());

    IndexRequest indexRequest =
        new IndexRequest(REGISTRY_INDEX_NAME)
            .id(lookupName)
            .source(registry)
            .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

    // If updating existing lookup, use seq_no for optimistic locking
    if (existingDoc.isExists()) {
      indexRequest.setIfSeqNo(existingDoc.getSeqNo());
      indexRequest.setIfPrimaryTerm(existingDoc.getPrimaryTerm());
    }

    try (ThreadContext.StoredContext ignored =
        client.threadPool().getThreadContext().stashContext()) {
      client.index(
          indexRequest,
          ActionListener.wrap(
              indexResponse ->
                  listener.onResponse(new StoreLookupResult(lookupName, version, rowCount)),
              indexFailure -> {
                if (indexFailure instanceof VersionConflictEngineException) {
                  listener.onFailure(
                      new OpenSearchStatusException(
                          "Lookup was updated concurrently, please retry", RestStatus.CONFLICT));
                } else {
                  listener.onFailure(indexFailure);
                }
              }));
    }
  }

  /** Result of storing a lookup */
  public static class StoreLookupResult {
    private final String lookupName;
    private final String version;
    private final int rowCount;

    public StoreLookupResult(String lookupName, String version, int rowCount) {
      this.lookupName = lookupName;
      this.version = version;
      this.rowCount = rowCount;
    }

    public String getLookupName() {
      return lookupName;
    }

    public String getVersion() {
      return version;
    }

    public int getRowCount() {
      return rowCount;
    }
  }
}
