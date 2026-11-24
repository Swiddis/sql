/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.plan.rel;

import java.util.List;
import lombok.Getter;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * Logical write sink for {@code collect}: writes every input row to {@code table} and produces a
 * single row {@code (rows_written, rows_failed)}. A dedicated node rather than Calcite's
 * TableSpool, because its output row type differs from its input's.
 */
@Getter
public class LogicalCollectSink extends SingleRel {

  public static final String ROWS_WRITTEN = "rows_written";
  public static final String ROWS_FAILED = "rows_failed";

  private final RelOptTable table;
  private final List<String> idFields;

  public LogicalCollectSink(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode input,
      RelOptTable table,
      List<String> idFields) {
    super(cluster, traits, input);
    this.table = table;
    this.idFields = List.copyOf(idFields);
  }

  public static LogicalCollectSink create(RelNode input, RelOptTable table, List<String> idFields) {
    RelOptCluster cluster = input.getCluster();
    return new LogicalCollectSink(
        cluster, cluster.traitSetOf(Convention.NONE), input, table, idFields);
  }

  /** The (rows_written, rows_failed) result row type, shared with the physical node. */
  public static RelDataType resultRowType(RelDataTypeFactory typeFactory) {
    RelDataType bigint = typeFactory.createSqlType(SqlTypeName.BIGINT);
    return typeFactory.builder().add(ROWS_WRITTEN, bigint).add(ROWS_FAILED, bigint).build();
  }

  @Override
  protected RelDataType deriveRowType() {
    return resultRowType(getCluster().getTypeFactory());
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new LogicalCollectSink(getCluster(), traitSet, sole(inputs), table, idFields);
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .item("table", table.getQualifiedName())
        .itemIf("id", idFields, !idFields.isEmpty());
  }
}
