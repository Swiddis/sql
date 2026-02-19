package org.example.calcite.poc.rel;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.example.calcite.poc.convention.IndexScanConvention;

/**
 * Table scan implementation for IndexScan engine.
 * This is the slowest but most universally compatible engine.
 */
public class IndexScanTableScan extends TableScan implements IndexScanConvention.IndexScanRel {

    public IndexScanTableScan(RelOptCluster cluster, RelOptTable table) {
        super(cluster, cluster.traitSetOf(IndexScanConvention.INSTANCE), table);
    }

    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
        // High cost - this is the slowest engine
        double rowCount = table.getRowCount();
        return planner.getCostFactory().makeCost(
            rowCount * 100,  // High CPU cost
            rowCount,         // Rows
            0                 // No I/O cost for this example
        );
    }

    @Override
    public String toString() {
        return "IndexScanTableScan(table=" + table.getQualifiedName() + ")";
    }
}
