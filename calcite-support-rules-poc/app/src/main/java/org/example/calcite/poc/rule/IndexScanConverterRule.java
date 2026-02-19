package org.example.calcite.poc.rule;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.example.calcite.poc.convention.IndexScanConvention;
import org.example.calcite.poc.rel.IndexScanTableScan;

/**
 * Converter rule from logical table scan to IndexScan engine.
 * This always succeeds because IndexScan supports everything via postprocessing.
 */
public class IndexScanConverterRule extends ConverterRule {

    public static final IndexScanConverterRule INSTANCE = new IndexScanConverterRule();

    private IndexScanConverterRule() {
        super(
            LogicalTableScan.class,
            Convention.NONE,
            IndexScanConvention.INSTANCE,
            "IndexScanConverterRule"
        );
    }

    @Override
    public RelNode convert(RelNode rel) {
        LogicalTableScan scan = (LogicalTableScan) rel;
        RelTraitSet traitSet = scan.getTraitSet().replace(IndexScanConvention.INSTANCE);

        return new IndexScanTableScan(
            scan.getCluster(),
            scan.getTable()
        );
    }
}
