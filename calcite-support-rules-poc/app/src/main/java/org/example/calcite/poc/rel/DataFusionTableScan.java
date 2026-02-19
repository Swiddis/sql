package org.example.calcite.poc.rel;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.example.calcite.poc.convention.DataFusionConvention;

import java.util.Set;

/**
 * Table scan implementation for DataFusion engine.
 * This is the fastest engine but with limited feature support.
 */
public class DataFusionTableScan extends TableScan implements DataFusionConvention.DataFusionRel {

    private final Set<String> pushedDownFeatures;

    public DataFusionTableScan(RelOptCluster cluster, RelOptTable table, Set<String> pushedDownFeatures) {
        super(cluster, cluster.traitSetOf(DataFusionConvention.INSTANCE), table);
        this.pushedDownFeatures = pushedDownFeatures;
    }

    public Set<String> getPushedDownFeatures() {
        return pushedDownFeatures;
    }

    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
        // Low cost - this is the fastest engine
        double rowCount = table.getRowCount();
        return planner.getCostFactory().makeCost(
            rowCount * 1,    // Low CPU cost - very fast
            rowCount,        // Rows
            0                // No I/O cost for this example
        );
    }

    @Override
    public String toString() {
        return "DataFusionTableScan(table=" + table.getQualifiedName() +
               ", pushedDown=" + pushedDownFeatures + ")";
    }
}
