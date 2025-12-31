/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.plugin.lookup.poc;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.action.ActionFuture;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.index.engine.VersionConflictEngineException;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.transport.client.node.NodeClient;

@RunWith(MockitoJUnitRunner.Silent.class)
public class LookupStorageMethodsTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private NodeClient client;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private ClusterService clusterService;

  @Mock private ActionFuture<GetResponse> getFuture;
  @Mock private GetResponse getResponse;
  @Mock private BulkResponse bulkResponse;
  @Mock private IndexResponse indexResponse;

  private LookupStoragePoc storage;

  @Before
  public void setup() {
    storage = new LookupStoragePoc(client, clusterService);
  }

  // ==================== buildDataFilter Tests ====================

  @Test
  public void testBuildDataFilter_Success() {
    // When
    BoolQueryBuilder filter = storage.buildDataFilter("my_lookup", "uuid-123");

    // Then
    assertNotNull(filter);
    String filterString = filter.toString();
    assertTrue("Filter should contain lookup_name", filterString.contains("lookup_name"));
    assertTrue("Filter should contain my_lookup", filterString.contains("my_lookup"));
    assertTrue("Filter should contain version", filterString.contains("version"));
    assertTrue("Filter should contain uuid-123", filterString.contains("uuid-123"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBuildDataFilter_NullLookupName() {
    storage.buildDataFilter(null, "uuid-123");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBuildDataFilter_EmptyLookupName() {
    storage.buildDataFilter("", "uuid-123");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBuildDataFilter_NullVersion() {
    storage.buildDataFilter("my_lookup", null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testBuildDataFilter_EmptyVersion() {
    storage.buildDataFilter("my_lookup", "");
  }

  // ==================== getFromRegistry Tests ====================

  @Test
  public void testGetFromRegistry_Success() {
    // Given
    when(client.get(any(GetRequest.class))).thenReturn(getFuture);
    when(getFuture.actionGet()).thenReturn(getResponse);

    // When
    storage.getFromRegistry(
        "my_lookup",
        new ActionListener<>() {
          @Override
          public void onResponse(GetResponse response) {
            assertEquals(getResponse, response);
          }

          @Override
          public void onFailure(Exception e) {
            fail("Should not fail");
          }
        });

    // Then
    ArgumentCaptor<GetRequest> captor = ArgumentCaptor.forClass(GetRequest.class);
    verify(client).get(captor.capture(), any());

    GetRequest request = captor.getValue();
    assertEquals(LookupStoragePoc.REGISTRY_INDEX_NAME, request.index());
    assertEquals("my_lookup", request.id());
  }

  // ==================== storeLookup Tests ====================

  @Test
  public void testStoreLookup_NewLookup_Success() {
    // Given: lookup doesn't exist
    doAnswer(
            invocation -> {
              ActionListener<GetResponse> listener = invocation.getArgument(1);
              when(getResponse.isExists()).thenReturn(false);
              listener.onResponse(getResponse);
              return null;
            })
        .when(client)
        .get(any(GetRequest.class), any());

    // Mock bulk write
    doAnswer(
            invocation -> {
              ActionListener<BulkResponse> listener = invocation.getArgument(1);
              when(bulkResponse.hasFailures()).thenReturn(false);
              listener.onResponse(bulkResponse);
              return null;
            })
        .when(client)
        .bulk(any(BulkRequest.class), any());

    // Mock registry update
    doAnswer(
            invocation -> {
              ActionListener<IndexResponse> listener = invocation.getArgument(1);
              listener.onResponse(indexResponse);
              return null;
            })
        .when(client)
        .index(any(IndexRequest.class), any());

    List<Map<String, Object>> data = new ArrayList<>();
    Map<String, Object> row1 = new HashMap<>();
    row1.put("field1", "value1");
    data.add(row1);

    // When
    storage.storeLookup(
        "my_lookup",
        data,
        "test_owner",
        new ActionListener<>() {
          @Override
          public void onResponse(LookupStoragePoc.StoreLookupResult result) {
            assertEquals("my_lookup", result.getLookupName());
            assertNotNull(result.getVersion());
            assertEquals(1, result.getRowCount());
          }

          @Override
          public void onFailure(Exception e) {
            fail("Should not fail: " + e.getMessage());
          }
        });

    // Then: verify data was tagged with lookup_name and version
    ArgumentCaptor<BulkRequest> bulkCaptor = ArgumentCaptor.forClass(BulkRequest.class);
    verify(client).bulk(bulkCaptor.capture(), any());

    BulkRequest bulkRequest = bulkCaptor.getValue();
    assertEquals(1, bulkRequest.requests().size());

    IndexRequest indexRequest = (IndexRequest) bulkRequest.requests().getFirst();
    Map<String, Object> source = indexRequest.sourceAsMap();
    assertTrue("Data should have lookup_name", source.containsKey("lookup_name"));
    assertEquals("my_lookup", source.get("lookup_name"));
    assertTrue("Data should have version", source.containsKey("version"));
    assertNotNull(source.get("version"));
  }

  @Test
  public void testStoreLookup_ExistingLookup_UsesSeqNo() {
    // Given: lookup exists with seq_no
    doAnswer(
            invocation -> {
              ActionListener<GetResponse> listener = invocation.getArgument(1);
              when(getResponse.isExists()).thenReturn(true);
              when(getResponse.getSeqNo()).thenReturn(5L);
              when(getResponse.getPrimaryTerm()).thenReturn(1L);
              listener.onResponse(getResponse);
              return null;
            })
        .when(client)
        .get(any(GetRequest.class), any());

    // Mock bulk write
    doAnswer(
            invocation -> {
              ActionListener<BulkResponse> listener = invocation.getArgument(1);
              when(bulkResponse.hasFailures()).thenReturn(false);
              listener.onResponse(bulkResponse);
              return null;
            })
        .when(client)
        .bulk(any(BulkRequest.class), any());

    // Mock registry update
    doAnswer(
            invocation -> {
              ActionListener<IndexResponse> listener = invocation.getArgument(1);
              listener.onResponse(indexResponse);
              return null;
            })
        .when(client)
        .index(any(IndexRequest.class), any());

    List<Map<String, Object>> data = new ArrayList<>();
    data.add(new HashMap<>());

    // When
    storage.storeLookup(
        "my_lookup",
        data,
        "test_owner",
        new ActionListener<>() {
          @Override
          public void onResponse(LookupStoragePoc.StoreLookupResult result) {
            // Success
          }

          @Override
          public void onFailure(Exception e) {
            fail("Should not fail");
          }
        });

    // Then: verify registry update used seq_no
    ArgumentCaptor<IndexRequest> indexCaptor = ArgumentCaptor.forClass(IndexRequest.class);
    verify(client).index(indexCaptor.capture(), any());

    IndexRequest indexRequest = indexCaptor.getValue();
    assertEquals(5L, indexRequest.ifSeqNo());
    assertEquals(1L, indexRequest.ifPrimaryTerm());
  }

  @Test
  public void testStoreLookup_ConcurrentUpdate_ReturnsConflict() {
    // Given: lookup exists
    doAnswer(
            invocation -> {
              ActionListener<GetResponse> listener = invocation.getArgument(1);
              when(getResponse.isExists()).thenReturn(true);
              when(getResponse.getSeqNo()).thenReturn(5L);
              when(getResponse.getPrimaryTerm()).thenReturn(1L);
              listener.onResponse(getResponse);
              return null;
            })
        .when(client)
        .get(any(GetRequest.class), any());

    // Mock bulk write success
    doAnswer(
            invocation -> {
              ActionListener<BulkResponse> listener = invocation.getArgument(1);
              when(bulkResponse.hasFailures()).thenReturn(false);
              listener.onResponse(bulkResponse);
              return null;
            })
        .when(client)
        .bulk(any(BulkRequest.class), any());

    // Mock registry update with version conflict
    doAnswer(
            invocation -> {
              ActionListener<IndexResponse> listener = invocation.getArgument(1);
              listener.onFailure(
                  new VersionConflictEngineException(
                      new ShardId("test", "test", 0), "my_lookup", "version conflict"));
              return null;
            })
        .when(client)
        .index(any(IndexRequest.class), any());

    List<Map<String, Object>> data = new ArrayList<>();
    data.add(new HashMap<>());

    // When
    storage.storeLookup(
        "my_lookup",
        data,
        "test_owner",
        new ActionListener<>() {
          @Override
          public void onResponse(LookupStoragePoc.StoreLookupResult result) {
            fail("Should fail with conflict");
          }

          @Override
          public void onFailure(Exception e) {
            assertTrue(
                "Should be OpenSearchStatusException", e instanceof OpenSearchStatusException);
            OpenSearchStatusException statusException = (OpenSearchStatusException) e;
            assertEquals(RestStatus.CONFLICT, statusException.status());
            assertTrue(
                "Message should mention concurrent update",
                e.getMessage().contains("updated concurrently"));
          }
        });
  }

  @Test
  public void testStoreLookup_BulkFailure_ReturnsError() {
    // Given
    doAnswer(
            invocation -> {
              ActionListener<GetResponse> listener = invocation.getArgument(1);
              when(getResponse.isExists()).thenReturn(false);
              listener.onResponse(getResponse);
              return null;
            })
        .when(client)
        .get(any(GetRequest.class), any());

    // Mock bulk write failure
    doAnswer(
            invocation -> {
              ActionListener<BulkResponse> listener = invocation.getArgument(1);
              when(bulkResponse.hasFailures()).thenReturn(true);
              when(bulkResponse.buildFailureMessage()).thenReturn("Bulk write failed");
              listener.onResponse(bulkResponse);
              return null;
            })
        .when(client)
        .bulk(any(BulkRequest.class), any());

    List<Map<String, Object>> data = new ArrayList<>();
    data.add(new HashMap<>());

    // When
    storage.storeLookup(
        "my_lookup",
        data,
        "test_owner",
        new ActionListener<>() {
          @Override
          public void onResponse(LookupStoragePoc.StoreLookupResult result) {
            fail("Should fail with bulk error");
          }

          @Override
          public void onFailure(Exception e) {
            assertTrue(
                "Should be OpenSearchStatusException", e instanceof OpenSearchStatusException);
            assertTrue(
                "Message should mention bulk failure", e.getMessage().contains("Failed to write"));
          }
        });
  }

  @Test
  public void testStoreLookup_MultipleRows_AllTagged() {
    // Given
    doAnswer(
            invocation -> {
              ActionListener<GetResponse> listener = invocation.getArgument(1);
              when(getResponse.isExists()).thenReturn(false);
              listener.onResponse(getResponse);
              return null;
            })
        .when(client)
        .get(any(GetRequest.class), any());

    doAnswer(
            invocation -> {
              ActionListener<BulkResponse> listener = invocation.getArgument(1);
              when(bulkResponse.hasFailures()).thenReturn(false);
              listener.onResponse(bulkResponse);
              return null;
            })
        .when(client)
        .bulk(any(BulkRequest.class), any());

    doAnswer(
            invocation -> {
              ActionListener<IndexResponse> listener = invocation.getArgument(1);
              listener.onResponse(indexResponse);
              return null;
            })
        .when(client)
        .index(any(IndexRequest.class), any());

    List<Map<String, Object>> data = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      Map<String, Object> row = new HashMap<>();
      row.put("field", "value" + i);
      data.add(row);
    }

    // When
    storage.storeLookup(
        "my_lookup",
        data,
        "test_owner",
        new ActionListener<>() {
          @Override
          public void onResponse(LookupStoragePoc.StoreLookupResult result) {
            assertEquals(3, result.getRowCount());
          }

          @Override
          public void onFailure(Exception e) {
            fail("Should not fail");
          }
        });

    // Then: verify all rows were tagged
    ArgumentCaptor<BulkRequest> bulkCaptor = ArgumentCaptor.forClass(BulkRequest.class);
    verify(client).bulk(bulkCaptor.capture(), any());

    BulkRequest bulkRequest = bulkCaptor.getValue();
    assertEquals(3, bulkRequest.requests().size());

    String version = null;
    for (int i = 0; i < 3; i++) {
      IndexRequest indexRequest = (IndexRequest) bulkRequest.requests().get(i);
      Map<String, Object> source = indexRequest.sourceAsMap();

      assertEquals("my_lookup", source.get("lookup_name"));
      assertTrue(source.containsKey("version"));

      // All rows should have same version
      if (version == null) {
        version = (String) source.get("version");
      } else {
        assertEquals("All rows should have same version", version, source.get("version"));
      }
    }
  }
}
