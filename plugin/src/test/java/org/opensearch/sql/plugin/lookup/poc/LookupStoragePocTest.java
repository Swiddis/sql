/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.plugin.lookup.poc;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.opensearch.sql.plugin.lookup.poc.LookupStoragePoc.DATA_INDEX_NAME;
import static org.opensearch.sql.plugin.lookup.poc.LookupStoragePoc.REGISTRY_INDEX_NAME;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.indices.create.CreateIndexResponse;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.action.ActionFuture;
import org.opensearch.transport.client.node.NodeClient;

@RunWith(MockitoJUnitRunner.Silent.class)
public class LookupStoragePocTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private NodeClient client;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private ClusterService clusterService;

  @Mock private ActionFuture<CreateIndexResponse> createIndexFuture;
  @Mock private CreateIndexResponse createIndexResponse;

  private LookupStoragePoc storage;

  @Before
  public void setup() {
    storage = new LookupStoragePoc(client, clusterService);
  }

  @Test
  public void testInitializeIndices_CreatesRegistryIndex() {
    // Given: indices don't exist
    when(clusterService.state().metadata().hasIndex(REGISTRY_INDEX_NAME)).thenReturn(false);
    when(clusterService.state().metadata().hasIndex(DATA_INDEX_NAME)).thenReturn(false);
    when(client.admin().indices().create(any(CreateIndexRequest.class)))
        .thenReturn(createIndexFuture);
    when(createIndexFuture.actionGet()).thenReturn(createIndexResponse);
    when(createIndexResponse.isAcknowledged()).thenReturn(true);

    // When
    storage.initializeIndices();

    // Then: verify registry index was created with correct settings
    ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
    verify(client.admin().indices(), times(2)).create(captor.capture());

    CreateIndexRequest registryRequest = captor.getAllValues().get(0);
    assertEquals(REGISTRY_INDEX_NAME, registryRequest.index());

    // Verify registry index has mapping
    assertNotNull(registryRequest.mappings());
    assertTrue(registryRequest.mappings().contains("lookup_name"));
    assertTrue(registryRequest.mappings().contains("version"));
    assertTrue(registryRequest.mappings().contains("owner"));
    assertTrue(registryRequest.mappings().contains("updated_at"));

    // Verify registry index settings
    assertEquals("true", registryRequest.settings().get("index.hidden"));
    assertEquals("1", registryRequest.settings().get("index.number_of_shards"));
  }

  @Test
  public void testInitializeIndices_CreatesDataIndex() {
    // Given: indices don't exist
    when(clusterService.state().metadata().hasIndex(REGISTRY_INDEX_NAME)).thenReturn(false);
    when(clusterService.state().metadata().hasIndex(DATA_INDEX_NAME)).thenReturn(false);
    when(client.admin().indices().create(any(CreateIndexRequest.class)))
        .thenReturn(createIndexFuture);
    when(createIndexFuture.actionGet()).thenReturn(createIndexResponse);
    when(createIndexResponse.isAcknowledged()).thenReturn(true);

    // When
    storage.initializeIndices();

    // Then: verify data index was created
    ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
    verify(client.admin().indices(), times(2)).create(captor.capture());

    CreateIndexRequest dataRequest = captor.getAllValues().get(1);
    assertEquals(DATA_INDEX_NAME, dataRequest.index());

    // Verify data index settings (hidden, dynamic mapping)
    assertEquals("true", dataRequest.settings().get("index.hidden"));
    assertEquals("1", dataRequest.settings().get("index.number_of_shards"));
  }

  @Test
  public void testInitializeIndices_SkipsIfAlreadyExists() {
    // Given: indices already exist
    when(clusterService.state().metadata().hasIndex(REGISTRY_INDEX_NAME)).thenReturn(true);
    when(clusterService.state().metadata().hasIndex(DATA_INDEX_NAME)).thenReturn(true);

    // When
    storage.initializeIndices();

    // Then: no create requests should be made
    verify(client.admin().indices(), never()).create(any(CreateIndexRequest.class));
  }

  @Test
  public void testInitializeIndices_IdempotentMultipleCalls() {
    // Given: indices don't exist
    when(clusterService.state().metadata().hasIndex(REGISTRY_INDEX_NAME)).thenReturn(false);
    when(clusterService.state().metadata().hasIndex(DATA_INDEX_NAME)).thenReturn(false);
    when(client.admin().indices().create(any(CreateIndexRequest.class)))
        .thenReturn(createIndexFuture);
    when(createIndexFuture.actionGet()).thenReturn(createIndexResponse);
    when(createIndexResponse.isAcknowledged()).thenReturn(true);

    // When: called multiple times
    storage.initializeIndices();
    storage.initializeIndices();
    storage.initializeIndices();

    // Then: indices should only be created once
    verify(client.admin().indices(), times(2)).create(any(CreateIndexRequest.class));
  }

  @Test(expected = RuntimeException.class)
  public void testInitializeIndices_ThrowsOnCreationFailure() {
    // Given: index creation fails
    when(clusterService.state().metadata().hasIndex(REGISTRY_INDEX_NAME)).thenReturn(false);
    when(clusterService.state().metadata().hasIndex(DATA_INDEX_NAME)).thenReturn(false);
    when(client.admin().indices().create(any(CreateIndexRequest.class)))
        .thenReturn(createIndexFuture);
    when(createIndexFuture.actionGet()).thenReturn(createIndexResponse);
    when(createIndexResponse.isAcknowledged()).thenReturn(false);

    // When/Then: should throw exception
    storage.initializeIndices();
  }

  @Test
  public void testInitializeIndices_HandlesException() {
    // Given: index creation throws exception
    when(clusterService.state().metadata().hasIndex(REGISTRY_INDEX_NAME)).thenReturn(false);
    when(clusterService.state().metadata().hasIndex(DATA_INDEX_NAME)).thenReturn(false);
    when(client.admin().indices().create(any(CreateIndexRequest.class)))
        .thenThrow(new RuntimeException("OpenSearch error"));

    // When/Then: should wrap and re-throw exception
    try {
      storage.initializeIndices();
      fail("Expected RuntimeException");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("Failed to create registry index"));
      assertTrue(e.getCause().getMessage().contains("OpenSearch error"));
    }
  }

  @Test
  public void testRegistryIndexExists_ReturnsTrue() {
    // Given
    when(clusterService.state().metadata().hasIndex(REGISTRY_INDEX_NAME)).thenReturn(true);

    // When/Then
    assertTrue(storage.registryIndexExists());
  }

  @Test
  public void testRegistryIndexExists_ReturnsFalse() {
    // Given
    when(clusterService.state().metadata().hasIndex(REGISTRY_INDEX_NAME)).thenReturn(false);

    // When/Then
    assertFalse(storage.registryIndexExists());
  }

  @Test
  public void testDataIndexExists_ReturnsTrue() {
    // Given
    when(clusterService.state().metadata().hasIndex(DATA_INDEX_NAME)).thenReturn(true);

    // When/Then
    assertTrue(storage.dataIndexExists());
  }

  @Test
  public void testDataIndexExists_ReturnsFalse() {
    // Given
    when(clusterService.state().metadata().hasIndex(DATA_INDEX_NAME)).thenReturn(false);

    // When/Then
    assertFalse(storage.dataIndexExists());
  }

  @Test
  public void testRegistryMapping_ContainsRequiredFields() {
    // Given: indices don't exist
    when(clusterService.state().metadata().hasIndex(REGISTRY_INDEX_NAME)).thenReturn(false);
    when(clusterService.state().metadata().hasIndex(DATA_INDEX_NAME)).thenReturn(false);
    when(client.admin().indices().create(any(CreateIndexRequest.class)))
        .thenReturn(createIndexFuture);
    when(createIndexFuture.actionGet()).thenReturn(createIndexResponse);
    when(createIndexResponse.isAcknowledged()).thenReturn(true);

    // When
    storage.initializeIndices();

    // Then: verify registry mapping has all required fields
    ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
    verify(client.admin().indices(), atLeast(1)).create(captor.capture());

    CreateIndexRequest registryRequest = captor.getAllValues().get(0);
    String mapping = registryRequest.mappings();

    // Verify all required fields are present with correct types
    assertNotNull("Mapping should not be null", mapping);
    assertTrue("Mapping should contain lookup_name", mapping.contains("lookup_name"));
    assertTrue("Mapping should contain keyword type", mapping.contains("keyword"));
    assertTrue("Mapping should contain version", mapping.contains("version"));
    assertTrue("Mapping should contain owner", mapping.contains("owner"));
    assertTrue("Mapping should contain updated_at", mapping.contains("updated_at"));
    assertTrue("Mapping should contain date type", mapping.contains("date"));
  }

  @Test
  public void testIndexSettings_ConfiguredForHiddenIndices() {
    // Given: indices don't exist
    when(clusterService.state().metadata().hasIndex(REGISTRY_INDEX_NAME)).thenReturn(false);
    when(clusterService.state().metadata().hasIndex(DATA_INDEX_NAME)).thenReturn(false);
    when(client.admin().indices().create(any(CreateIndexRequest.class)))
        .thenReturn(createIndexFuture);
    when(createIndexFuture.actionGet()).thenReturn(createIndexResponse);
    when(createIndexResponse.isAcknowledged()).thenReturn(true);

    // When
    storage.initializeIndices();

    // Then: verify both indices are configured as hidden
    ArgumentCaptor<CreateIndexRequest> captor = ArgumentCaptor.forClass(CreateIndexRequest.class);
    verify(client.admin().indices(), times(2)).create(captor.capture());

    for (CreateIndexRequest request : captor.getAllValues()) {
      assertEquals("true", request.settings().get("index.hidden"));
      assertEquals("1", request.settings().get("index.number_of_shards"));
      assertEquals("1", request.settings().get("index.number_of_replicas"));
      assertEquals("0-2", request.settings().get("index.auto_expand_replicas"));
    }
  }

  @Test
  public void testThreadContextStashing_CalledDuringIndexCreation() {
    // Given: indices don't exist
    when(clusterService.state().metadata().hasIndex(REGISTRY_INDEX_NAME)).thenReturn(false);
    when(clusterService.state().metadata().hasIndex(DATA_INDEX_NAME)).thenReturn(false);
    when(client.admin().indices().create(any(CreateIndexRequest.class)))
        .thenReturn(createIndexFuture);
    when(createIndexFuture.actionGet()).thenReturn(createIndexResponse);
    when(createIndexResponse.isAcknowledged()).thenReturn(true);

    // When
    storage.initializeIndices();

    // Then: verify thread context was stashed (for security context isolation)
    verify(client.threadPool().getThreadContext(), times(2)).stashContext();
  }
}
