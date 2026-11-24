/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.storage.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.calcite.rel.type.RelDataType;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.DocWriteRequest;
import org.opensearch.action.bulk.BackoffPolicy;
import org.opensearch.action.bulk.BulkItemResponse;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.sql.opensearch.client.OpenSearchClient;

/**
 * Batches rows into bulk index requests for {@code collect}. Rejected items (429) are retried with
 * backoff; any other failed item fails the whole query with the counts and the first reason.
 */
final class CollectBulkWriter {

  /** Outcome of a successful write; {@code rowsFailed} is 0 because any failure throws. */
  record Result(long rowsWritten, long rowsFailed) {}

  private final OpenSearchClient client;
  private final String index;
  private final List<String> names;
  private final List<RelDataType> types;
  private final List<Integer> idPositions;
  private final int batchSize;
  private BulkRequest bulk = new BulkRequest();
  private long written = 0;

  CollectBulkWriter(
      OpenSearchClient client,
      String index,
      List<String> names,
      List<RelDataType> types,
      List<String> idFields,
      int batchSize) {
    this.client = client;
    this.index = index;
    this.names = names;
    this.types = types;
    this.batchSize = batchSize;
    this.idPositions = new ArrayList<>();
    for (String f : idFields) {
      int pos = names.indexOf(f);
      if (pos < 0) {
        throw new IllegalArgumentException("collect id field [" + f + "] is not in the input");
      }
      idPositions.add(pos);
    }
  }

  /** Adds a row; all-null rows are skipped. May flush a full batch (and so may throw). */
  void add(Object row) {
    Object[] values = (row instanceof Object[] arr) ? arr : new Object[] {row};
    Map<String, Object> doc = CollectDocs.toDocument(names, types, values);
    if (doc == null) {
      return;
    }
    IndexRequest req = new IndexRequest(index).source(doc);
    if (!idPositions.isEmpty()) {
      List<Object> idValues = new ArrayList<>();
      for (int p : idPositions) {
        idValues.add(CollectDocs.toJson(values[p], types.get(p)));
      }
      req.id(CollectDocs.documentId(idValues)).opType(DocWriteRequest.OpType.INDEX);
    }
    bulk.add(req);
    if (bulk.numberOfActions() >= batchSize) {
      flush();
    }
  }

  Result finish() {
    if (bulk.numberOfActions() > 0) {
      flush();
    }
    return new Result(written, 0);
  }

  private void flush() {
    BulkRequest request = bulk;
    bulk = new BulkRequest();
    int total = request.numberOfActions();
    BulkRequest pending = request;
    java.util.Iterator<TimeValue> backoff = BackoffPolicy.exponentialBackoff().iterator();
    long okInBatch = 0;
    while (true) {
      BulkResponse response = client.bulk(pending);
      BulkRequest retry = new BulkRequest();
      BulkItemResponse firstFailure = null;
      long failed = 0;
      long retried = 0;
      for (BulkItemResponse item : response.getItems()) {
        if (!item.isFailed()) {
          okInBatch++;
        } else if (item.getFailure().getStatus() == RestStatus.TOO_MANY_REQUESTS
            && backoff.hasNext()) {
          retry.add((IndexRequest) pending.requests().get(item.getItemId()));
          retried++;
        } else {
          failed++;
          if (firstFailure == null) {
            firstFailure = item;
          }
        }
      }
      if (failed > 0) {
        written += okInBatch;
        BulkItemResponse.Failure f = firstFailure.getFailure();
        throw new OpenSearchStatusException(
            String.format(
                "collect failed: rows_written=%d rows_failed=%d (of %d in the failing bulk write"
                    + " to [%s]). First failure: [%s] %s",
                written, failed + retried, total, index, f.getStatus(), f.getMessage()),
            f.getStatus());
      }
      if (retried == 0) {
        written += okInBatch;
        return;
      }
      try {
        Thread.sleep(backoff.next().millis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new OpenSearchStatusException(
            "collect interrupted while retrying rejected writes", RestStatus.SERVICE_UNAVAILABLE);
      }
      pending = retry;
    }
  }
}
