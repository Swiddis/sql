/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.storage.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongConsumer;
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

  /**
   * Upper bound on the total time one batch sleeps between retries of rejected (429) items; past it
   * the rejection fails the query. shortcut: the sleep still blocks the SQL worker thread, which
   * the synchronous bulk call blocks anyway; the bound keeps the stall short.
   */
  static final long MAX_BACKOFF_MILLIS = 5000;

  private final OpenSearchClient client;
  private final String index;
  private final List<String> names;
  private final List<RelDataType> types;
  private final List<String> idFields;
  private final List<Integer> idPositions;
  private final int batchSize;

  /** SCALAR row format (row is the value), non-scalar (row is Object[]), or null to guess. */
  private final Boolean scalarRow;

  private Map<String, RelDataType> memberTypes = Map.of();
  private LongConsumer sleeper = CollectBulkWriter::sleep;
  private BulkRequest bulk = new BulkRequest();
  private long written = 0;

  /** Guesses the row shape from the row object; prefer the constructor taking the row format. */
  CollectBulkWriter(
      OpenSearchClient client,
      String index,
      List<String> names,
      List<RelDataType> types,
      List<String> idFields,
      int batchSize) {
    this(client, index, names, types, idFields, batchSize, null);
  }

  /**
   * @param scalarRow whether the input's {@code PhysType} format is {@code JavaRowFormat.SCALAR}
   */
  CollectBulkWriter(
      OpenSearchClient client,
      String index,
      List<String> names,
      List<RelDataType> types,
      List<String> idFields,
      int batchSize,
      Boolean scalarRow) {
    this.client = client;
    this.index = index;
    this.names = names;
    this.types = types;
    this.batchSize = batchSize;
    this.scalarRow = scalarRow;
    this.idFields = List.copyOf(idFields);
    this.idPositions = new ArrayList<>();
    for (String f : idFields) {
      int pos = names.indexOf(f);
      if (pos < 0) {
        throw new IllegalArgumentException("collect id field [" + f + "] is not in the input");
      }
      idPositions.add(pos);
    }
  }

  /** Types of struct members by dotted path, for columns whose struct value carries no types. */
  CollectBulkWriter withMemberTypes(Map<String, RelDataType> memberTypes) {
    this.memberTypes = memberTypes;
    return this;
  }

  CollectBulkWriter withSleeper(LongConsumer sleeper) {
    this.sleeper = sleeper;
    return this;
  }

  /** Adds a row; all-null rows are skipped. May flush a full batch (and so may throw). */
  void add(Object row) {
    Object[] values =
        scalarRow != null
            ? CollectDocs.rowValues(row, scalarRow)
            : (row instanceof Object[] arr) ? arr : new Object[] {row};
    Map<String, Object> doc = CollectDocs.toDocument(names, types, values, memberTypes);
    if (doc == null) {
      return;
    }
    IndexRequest req = new IndexRequest(index).source(doc);
    if (!idPositions.isEmpty()) {
      List<Object> idValues = new ArrayList<>();
      for (int p : idPositions) {
        idValues.add(CollectDocs.toJson(values[p], types.get(p)));
      }
      if (idValues.stream().allMatch(Objects::isNull)) {
        // All rows with a null key would share one _id and silently overwrite each other.
        throw new OpenSearchStatusException(
            "collect id field(s) "
                + idFields
                + " are all null in a row; every id field is null,"
                + " so the row has no identity. Filter these rows out (where isnotnull(...)) or"
                + " give them a value (fillnull) before collect.",
            RestStatus.BAD_REQUEST);
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
    java.util.Iterator<Long> backoff = boundedBackoff().iterator();
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
      sleeper.accept(backoff.next());
      pending = retry;
    }
  }

  /**
   * The default exponential delays, cut off so their sum stays within {@link #MAX_BACKOFF_MILLIS}.
   */
  private static List<Long> boundedBackoff() {
    List<Long> delays = new ArrayList<>();
    long total = 0;
    for (TimeValue t : BackoffPolicy.exponentialBackoff()) {
      if (total + t.millis() > MAX_BACKOFF_MILLIS) {
        break;
      }
      total += t.millis();
      delays.add(t.millis());
    }
    return delays;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OpenSearchStatusException(
          "collect interrupted while retrying rejected writes", RestStatus.SERVICE_UNAVAILABLE);
    }
  }
}
