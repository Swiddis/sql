/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.storage.scan;

import java.lang.reflect.Method;
import java.util.List;
import lombok.Getter;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.opensearch.sql.calcite.plan.rel.LogicalCollectSink;
import org.opensearch.sql.opensearch.storage.OpenSearchIndex;

/**
 * Physical write sink for {@code collect}: drains the input, bulk-writes every row to the
 * destination index and returns one row {@code (rows_written, rows_failed)}. Any rejected row fails
 * the query (see {@link CollectBulkWriter}).
 */
@Getter
public class EnumerableCollectSink extends SingleRel implements EnumerableRel {

  // shortcut: fixed batch size with no byte cap; make it a setting if wide rows need smaller bulks.
  static final int BATCH_SIZE = 1000;

  /**
   * Resolved once so codegen binds to the declared Enumerable parameter, not AbstractEnumerable.
   */
  private static final Method WRITE_ALL =
      Types.lookupMethod(EnumerableCollectSink.class, "writeAll", Enumerable.class);

  private final RelOptTable table;
  private final OpenSearchIndex osIndex;
  private final String indexName;
  private final List<String> idFields;

  /** The column names as the query wrote them; see {@link LogicalCollectSink#getFieldNames}. */
  private final List<String> fieldNames;

  public EnumerableCollectSink(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode input,
      RelOptTable table,
      List<String> idFields,
      List<String> fieldNames) {
    super(cluster, traitSet, input);
    this.table = table;
    this.osIndex = table.unwrap(OpenSearchIndex.class);
    this.indexName = table.getQualifiedName().get(table.getQualifiedName().size() - 1);
    this.idFields = List.copyOf(idFields);
    this.fieldNames = List.copyOf(fieldNames);
    if (fieldNames.size() != input.getRowType().getFieldCount()) {
      throw new IllegalStateException(
          "collect: planned input has "
              + input.getRowType().getFieldCount()
              + " columns, expected "
              + fieldNames);
    }
  }

  @Override
  protected RelDataType deriveRowType() {
    return LogicalCollectSink.resultRowType(getCluster().getTypeFactory());
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new EnumerableCollectSink(
        getCluster(), traitSet, sole(inputs), table, idFields, fieldNames);
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .item("table", table.getQualifiedName())
        .itemIf("id", idFields, !idFields.isEmpty());
  }

  @Override
  public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
    // Consume the child via visitChild (not a Scannable short-circuit) so collect accepts any
    // input shape; a bare scan's own scan() still drives unbounded PIT paging.
    BlockBuilder builder = new BlockBuilder();
    Result inputResult = implementor.visitChild(this, 0, (EnumerableRel) getInput(), pref);
    PhysType physType =
        PhysTypeImpl.of(implementor.getTypeFactory(), getRowType(), pref.preferArray());
    Expression inputEnumerable = builder.append("inputEnumerable", inputResult.block);
    Expression op = implementor.stash(this, EnumerableCollectSink.class);
    builder.add(Expressions.return_(null, Expressions.call(op, WRITE_ALL, inputEnumerable)));
    return implementor.result(physType, builder.toBlock());
  }

  /** Writes every input row, then returns the single result row. Called from generated code. */
  public Enumerable<@Nullable Object> writeAll(Enumerable<@Nullable Object> input) {
    // Names from the query, types by position from the planned input (the planner keeps types).
    List<RelDataTypeField> fields = getInput().getRowType().getFieldList();
    CollectBulkWriter writer =
        new CollectBulkWriter(
            osIndex.getClient(),
            indexName,
            fieldNames,
            fields.stream().map(RelDataTypeField::getType).toList(),
            idFields,
            BATCH_SIZE);
    try (var rows = input.enumerator()) {
      while (rows.moveNext()) {
        writer.add(rows.current());
      }
    }
    CollectBulkWriter.Result result = writer.finish();
    return Linq4j.singletonEnumerable(new Object[] {result.rowsWritten(), result.rowsFailed()});
  }
}
