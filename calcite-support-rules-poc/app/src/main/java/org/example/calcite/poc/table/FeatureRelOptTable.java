package org.example.calcite.poc.table;

import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.prepare.RelOptTableImpl;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.ColumnStrategy;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.SqlAccessType;
import org.apache.calcite.sql.validate.SqlModality;
import org.apache.calcite.sql.validate.SqlMonotonicity;
import org.apache.calcite.util.ImmutableBitSet;

import java.util.List;
import java.util.Set;

/**
 * RelOptTable implementation that wraps a FeatureTable and provides
 * access to its required features during optimization.
 */
public class FeatureRelOptTable implements RelOptTable {

    private final RelOptSchema schema;
    private final RelDataType rowType;
    private final List<String> qualifiedName;
    private final FeatureTable featureTable;

    public FeatureRelOptTable(
        RelOptSchema schema,
        RelDataType rowType,
        List<String> qualifiedName,
        FeatureTable featureTable) {
        this.schema = schema;
        this.rowType = rowType;
        this.qualifiedName = qualifiedName;
        this.featureTable = featureTable;
    }

    @Override
    public List<String> getQualifiedName() {
        return qualifiedName;
    }

    @Override
    public double getRowCount() {
        return featureTable.getStatistic().getRowCount();
    }

    @Override
    public RelDataType getRowType() {
        return rowType;
    }

    @Override
    public RelOptSchema getRelOptSchema() {
        return schema;
    }

    @Override
    public RelNode toRel(ToRelContext context) {
        return LogicalTableScan.create(context.getCluster(), this, List.of());
    }

    @Override
    public List<RelCollation> getCollationList() {
        return featureTable.getStatistic().getCollations();
    }

    @Override
    public RelDistribution getDistribution() {
        return featureTable.getStatistic().getDistribution();
    }

    @Override
    public boolean isKey(ImmutableBitSet columns) {
        return featureTable.getStatistic().isKey(columns);
    }

    @Override
    public List<RelReferentialConstraint> getReferentialConstraints() {
        return featureTable.getStatistic().getReferentialConstraints();
    }

    @Override
    public Expression getExpression(Class clazz) {
        throw new UnsupportedOperationException();
    }

    @Override
    public RelOptTable extend(List<RelDataTypeField> extendedFields) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ColumnStrategy> getColumnStrategies() {
        return RelOptTableImpl.columnStrategies(this);
    }

    @Override
    public List<ImmutableBitSet> getKeys() {
        return featureTable.getStatistic().getKeys();
    }

    public Set<String> getRequiredFeatures() {
        return featureTable.getRequiredFeatures();
    }

    @Override
    public <T> T unwrap(Class<T> clazz) {
        if (clazz.isInstance(this)) {
            return clazz.cast(this);
        }
        if (clazz.isInstance(featureTable)) {
            return clazz.cast(featureTable);
        }
        return null;
    }
}
