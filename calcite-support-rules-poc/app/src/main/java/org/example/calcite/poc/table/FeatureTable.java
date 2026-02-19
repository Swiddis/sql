package org.example.calcite.poc.table;

import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelDistributions;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.util.ImmutableBitSet;

import java.util.List;
import java.util.Set;

/**
 * A table implementation that tracks which features are required for queries against it.
 * This allows the optimizer to determine which execution engine can handle the query.
 */
public class FeatureTable extends AbstractTable {

    private final String name;
    private final Set<String> requiredFeatures;
    private final RelDataType rowType;
    private final double rowCount;

    public FeatureTable(String name, Set<String> requiredFeatures, RelDataType rowType, double rowCount) {
        this.name = name;
        this.requiredFeatures = requiredFeatures;
        this.rowType = rowType;
        this.rowCount = rowCount;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        return rowType;
    }

    @Override
    public Statistic getStatistic() {
        return Statistics.of(rowCount, List.of(), List.of());
    }

    public Set<String> getRequiredFeatures() {
        return requiredFeatures;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "FeatureTable(" + name + ", features=" + requiredFeatures + ")";
    }
}
