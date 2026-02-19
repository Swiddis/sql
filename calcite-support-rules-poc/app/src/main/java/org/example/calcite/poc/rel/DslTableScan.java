package org.example.calcite.poc.rel;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.example.calcite.poc.convention.DslConvention;

import java.util.Set;

/**
 * Table scan implementation for DSL engine.
 * This is the middle-tier engine with moderate performance and good feature support.
 */
public class DslTableScan extends TableScan implements DslConvention.DslRel {

    private final Set<String> pushedDownFeatures;

    public DslTableScan(RelOptCluster cluster, RelOptTable table, Set<String> pushedDownFeatures) {
        super(cluster, cluster.traitSetOf(DslConvention.INSTANCE), table);
        this.pushedDownFeatures = pushedDownFeatures;
    }

    public Set<String> getPushedDownFeatures() {
        return pushedDownFeatures;
    }

    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
        // Medium cost - faster than IndexScan, slower than DataFusion
        double rowCount = table.getRowCount();
        return planner.getCostFactory().makeCost(
            rowCount * 10,   // Medium CPU cost
            rowCount,        // Rows
            0                // No I/O cost for this example
        );
    }

    @Override
    public String toString() {
        return "DslTableScan(table=" + table.getQualifiedName() +
               ", pushedDown=" + pushedDownFeatures + ")";
    }
}
