/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.plan.rel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.metadata.RelColumnOrigin;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
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

  /**
   * The input's column names as the query wrote them, captured when the sink is built. The planner
   * may swap the input for an equivalent node with other names (a rename-only Project merged into a
   * scan keeps the source names), so the writer must use these, not the planned input's.
   */
  private final List<String> fieldNames;

  /**
   * Types of the members of struct columns, by dotted output path ({@code h.ts}). A struct column's
   * own type has no member types, and a projection such as {@code fields h} drops the dotted
   * columns that carry them, so they are read from the source table when the sink is built.
   */
  private final Map<String, RelDataType> memberTypes;

  public LogicalCollectSink(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode input,
      RelOptTable table,
      List<String> idFields,
      List<String> fieldNames,
      Map<String, RelDataType> memberTypes) {
    super(cluster, traits, input);
    this.table = table;
    this.idFields = List.copyOf(idFields);
    this.fieldNames = List.copyOf(fieldNames);
    this.memberTypes = Map.copyOf(memberTypes);
  }

  public static LogicalCollectSink create(RelNode input, RelOptTable table, List<String> idFields) {
    RelOptCluster cluster = input.getCluster();
    return new LogicalCollectSink(
        cluster,
        cluster.traitSetOf(Convention.NONE),
        input,
        table,
        idFields,
        input.getRowType().getFieldNames(),
        structMemberTypes(input));
  }

  /**
   * For each column that comes unchanged from one source column, the source table's dotted columns
   * under that column, renamed under the output column's name.
   */
  static Map<String, RelDataType> structMemberTypes(RelNode input) {
    Map<String, RelDataType> out = new LinkedHashMap<>();
    RelMetadataQuery mq = input.getCluster().getMetadataQuery();
    List<String> names = input.getRowType().getFieldNames();
    for (int i = 0; i < names.size(); i++) {
      Set<RelColumnOrigin> origins = mq.getColumnOrigins(input, i);
      if (origins == null || origins.size() != 1) {
        continue;
      }
      RelColumnOrigin origin = origins.iterator().next();
      if (origin.isDerived()) {
        continue;
      }
      List<RelDataTypeField> source = origin.getOriginTable().getRowType().getFieldList();
      String prefix = source.get(origin.getOriginColumnOrdinal()).getName() + ".";
      for (RelDataTypeField f : source) {
        if (f.getName().startsWith(prefix)) {
          out.put(names.get(i) + "." + f.getName().substring(prefix.length()), f.getType());
        }
      }
    }
    return out;
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
    return new LogicalCollectSink(
        getCluster(), traitSet, sole(inputs), table, idFields, fieldNames, memberTypes);
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .item("table", table.getQualifiedName())
        .itemIf("id", idFields, !idFields.isEmpty());
  }
}
