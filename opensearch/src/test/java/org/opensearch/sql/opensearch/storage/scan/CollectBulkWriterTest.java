/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.storage.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.DocWriteRequest;
import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.sql.calcite.utils.OpenSearchTypeFactory;
import org.opensearch.sql.opensearch.client.OpenSearchClient;

/** Unit tests for the collect bulk writer (no cluster). */
class CollectBulkWriterTest {

  private static final RelDataType BIGINT =
      OpenSearchTypeFactory.TYPE_FACTORY.createSqlType(SqlTypeName.BIGINT);
  private static final RelDataType VARCHAR =
      OpenSearchTypeFactory.TYPE_FACTORY.createSqlType(SqlTypeName.VARCHAR);

  private final List<BulkRequest> seen = new ArrayList<>();

  /** A client whose bulk answers item i with {@code outcome(i)} (null = success). */
  private OpenSearchClient client(Function<Integer, BulkItemResponse.Failure> outcome) {
    OpenSearchClient client = mock(OpenSearchClient.class);
    when(client.bulk(any()))
        .thenAnswer(
            inv -> {
              BulkRequest req = inv.getArgument(0);
              seen.add(req);
              BulkItemResponse[] items = new BulkItemResponse[req.numberOfActions()];
              for (int i = 0; i < items.length; i++) {
                BulkItemResponse.Failure f = outcome.apply(i);
                DocWriteRequest<?> r = req.requests().get(i);
                items[i] =
                    f != null
                        ? new BulkItemResponse(i, r.opType(), f)
                        : new BulkItemResponse(
                            i,
                            r.opType(),
                            new IndexResponse(
                                new ShardId("dest", "_na_", 0), "id" + i, 1, 1, 1, true));
              }
              return new BulkResponse(items, 1);
            });
    return client;
  }

  private static BulkItemResponse.Failure failure(RestStatus status, String reason) {
    return new BulkItemResponse.Failure("dest", "x", new RuntimeException(reason), status);
  }

  private CollectBulkWriter writer(OpenSearchClient c, List<String> idFields, int batch) {
    return new CollectBulkWriter(
        c, "dest", List.of("host", "n"), List.of(VARCHAR, BIGINT), idFields, batch);
  }

  /** Would have caught T1/T5/T6/T10: every non-429 item failure was swallowed (HTTP 200). */
  @Test
  void anyFailedItemFailsTheQueryWithCountsAndFirstReason() {
    CollectBulkWriter w =
        writer(
            client(
                i -> i == 1 ? failure(RestStatus.BAD_REQUEST, "mapper_parsing_exception x") : null),
            List.of(),
            100);
    w.add(new Object[] {"a", 1L});
    w.add(new Object[] {"b", 2L});
    w.add(new Object[] {"c", 3L});
    OpenSearchStatusException e = assertThrows(OpenSearchStatusException.class, w::finish);
    assertEquals(RestStatus.BAD_REQUEST, e.status());
    assertTrue(e.getMessage().contains("rows_failed=1"), e.getMessage());
    assertTrue(e.getMessage().contains("mapper_parsing_exception x"), e.getMessage());
    assertTrue(e.getMessage().contains("rows_written=2"), e.getMessage());
  }

  /**
   * Would have caught review bug B: a null id after earlier batches were written failed the query
   * without saying how much had been written.
   */
  @Test
  void nullIdInALaterBatchReportsRowsWritten() {
    CollectBulkWriter w = writer(client(i -> null), List.of("host"), 2);
    for (int i = 0; i < 4; i++) {
      w.add(new Object[] {"h" + i, 1L});
    }
    OpenSearchStatusException e =
        assertThrows(OpenSearchStatusException.class, () -> w.add(new Object[] {null, 1L}));
    assertEquals(RestStatus.BAD_REQUEST, e.status());
    assertTrue(e.getMessage().contains("rows_written=4"), e.getMessage());
    assertTrue(e.getMessage().contains("rows_failed=1"), e.getMessage());
    assertTrue(e.getMessage().contains("all null"), e.getMessage());
  }

  /** Would have caught T13: a user with no write grant on dest got HTTP 200 and 0 rows. */
  @Test
  void securityItemFailureSurfacesAs403() {
    CollectBulkWriter w =
        writer(client(i -> failure(RestStatus.FORBIDDEN, "no permissions")), List.of(), 100);
    w.add(new Object[] {"a", 1L});
    OpenSearchStatusException e = assertThrows(OpenSearchStatusException.class, w::finish);
    assertEquals(RestStatus.FORBIDDEN, e.status());
  }

  @Test
  void failureInAnEarlierBatchStopsTheWrite() {
    CollectBulkWriter w =
        writer(client(i -> failure(RestStatus.BAD_REQUEST, "boom")), List.of(), 2);
    w.add(new Object[] {"a", 1L});
    assertThrows(OpenSearchStatusException.class, () -> w.add(new Object[] {"b", 2L}));
    assertEquals(1, seen.size());
  }

  /** A 429 on the first bulk is retried alone; only the rejected item is resent. */
  @Test
  void rejectedItemsAreRetriedAndThenSucceed() {
    CollectBulkWriter w =
        writer(
            client(
                i ->
                    seen.size() == 1 && i == 0
                        ? failure(RestStatus.TOO_MANY_REQUESTS, "busy")
                        : null),
            List.of(),
            100);
    w.add(new Object[] {"a", 1L});
    w.add(new Object[] {"b", 2L});
    assertEquals(2, w.finish().rowsWritten());
    assertEquals(List.of(2, 1), seen.stream().map(BulkRequest::numberOfActions).toList());
  }

  @Test
  void successReturnsCountsAndSkipsAllNullRows() {
    CollectBulkWriter w = writer(client(i -> null), List.of(), 100);
    w.add(new Object[] {"a", 1L});
    w.add(new Object[] {null, null});
    w.add(new Object[] {"b", null});
    CollectBulkWriter.Result r = w.finish();
    assertEquals(2, r.rowsWritten());
    assertEquals(0, r.rowsFailed());
  }

  @Test
  void batchesAtBatchSize() {
    CollectBulkWriter w = writer(client(i -> null), List.of(), 2);
    for (int i = 0; i < 5; i++) {
      w.add(new Object[] {"a", (long) i});
    }
    assertEquals(5, w.finish().rowsWritten());
    assertEquals(List.of(2, 2, 1), seen.stream().map(BulkRequest::numberOfActions).toList());
  }

  /** Would have caught T2/T9/T12: random _id made every re-run or retry append duplicates. */
  @Test
  void idOptionUsesDeterministicIdAndIndexOpType() {
    CollectBulkWriter w = writer(client(i -> null), List.of("host"), 100);
    w.add(new Object[] {"a", 1L});
    w.add(new Object[] {"a", 2L});
    w.finish();
    IndexRequest r0 = (IndexRequest) seen.get(0).requests().get(0);
    IndexRequest r1 = (IndexRequest) seen.get(0).requests().get(1);
    assertNotNull(r0.id());
    assertEquals(CollectDocs.documentId(List.of("a")), r0.id());
    assertEquals(r0.id(), r1.id());
    assertEquals(DocWriteRequest.OpType.INDEX, r0.opType());
  }

  @Test
  void withoutIdOptionIdsAreAutoGenerated() {
    CollectBulkWriter w = writer(client(i -> null), List.of(), 100);
    w.add(new Object[] {"a", 1L});
    w.finish();
    assertNull(((IndexRequest) seen.get(0).requests().get(0)).id());
  }

  /** Would have caught review bug 6: rows whose id fields are all null collapsed into one _id. */
  @Test
  void rowWithAllNullIdFieldsFailsLoudly() {
    CollectBulkWriter w = writer(client(i -> null), List.of("host"), 100);
    OpenSearchStatusException e =
        assertThrows(OpenSearchStatusException.class, () -> w.add(new Object[] {null, 1L}));
    assertEquals(RestStatus.BAD_REQUEST, e.status());
    assertTrue(e.getMessage().contains("host"), e.getMessage());
  }

  /** A null in only some id fields is a legitimate key part and still hashes distinctly. */
  @Test
  void partiallyNullIdIsAccepted() {
    CollectBulkWriter w = writer(client(i -> null), List.of("host", "n"), 100);
    w.add(new Object[] {"a", null});
    assertEquals(1, w.finish().rowsWritten());
  }

  /** Would have caught review bug 10: unbounded (about 13 s per batch) sleeping on the worker. */
  @Test
  void retryBackoffTotalIsBounded() {
    List<Long> slept = new ArrayList<>();
    CollectBulkWriter w =
        writer(client(i -> failure(RestStatus.TOO_MANY_REQUESTS, "busy")), List.of(), 100)
            .withSleeper(slept::add);
    w.add(new Object[] {"a", 1L});
    OpenSearchStatusException e = assertThrows(OpenSearchStatusException.class, w::finish);
    assertEquals(RestStatus.TOO_MANY_REQUESTS, e.status());
    assertTrue(slept.size() > 0);
    assertTrue(
        slept.stream().mapToLong(Long::longValue).sum() <= CollectBulkWriter.MAX_BACKOFF_MILLIS,
        slept.toString());
  }

  /** Would have caught review bug 11: with SCALAR row format the row is the single value. */
  @Test
  void scalarRowFormatTreatsObjectArrayAsTheColumnValue() {
    RelDataType row =
        OpenSearchTypeFactory.TYPE_FACTORY.builder().add("a", VARCHAR).add("b", BIGINT).build();
    List<IndexRequest> sent = new ArrayList<>();
    OpenSearchClient c = client(i -> null);
    CollectBulkWriter w =
        new CollectBulkWriter(c, "dest", List.of("r"), List.of(row), List.of(), 100, true);
    w.add(new Object[] {"x", 2L});
    w.finish();
    assertEquals(
        java.util.Map.of("r", java.util.Map.of("a", "x", "b", 2)),
        ((IndexRequest) seen.get(0).requests().get(0)).sourceAsMap());
  }
}
