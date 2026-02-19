package org.example.calcite.poc.rule;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.example.calcite.poc.convention.DslConvention;
import org.example.calcite.poc.rel.DslTableScan;
import org.example.calcite.poc.table.FeatureRelOptTable;
import org.example.calcite.poc.table.FeatureTable;

import java.util.HashSet;
import java.util.Set;

/**
 * Converter rule from logical table scan to DSL engine.
 * This succeeds only if all required features are supported by DSL.
 */
public class DslConverterRule extends ConverterRule {

    public static final DslConverterRule INSTANCE = new DslConverterRule();

    private DslConverterRule() {
        super(
            LogicalTableScan.class,
            Convention.NONE,
            DslConvention.INSTANCE,
            "DslConverterRule"
        );
    }

    @Override
    public RelNode convert(RelNode rel) {
        LogicalTableScan scan = (LogicalTableScan) rel;

        // Get required features from the table
        Set<String> requiredFeatures = getRequiredFeatures(scan);

        // Check if DSL supports all required features
        for (String feature : requiredFeatures) {
            if (!DslConvention.supportsFeature(feature)) {
                return null; // DSL cannot handle this query
            }
        }

        RelTraitSet traitSet = scan.getTraitSet().replace(DslConvention.INSTANCE);

        return new DslTableScan(
            scan.getCluster(),
            scan.getTable(),
            requiredFeatures
        );
    }

    private Set<String> getRequiredFeatures(LogicalTableScan scan) {
        // Extract required features from the table
        if (scan.getTable() instanceof FeatureRelOptTable) {
            return ((FeatureRelOptTable) scan.getTable()).getRequiredFeatures();
        }
        FeatureTable table = scan.getTable().unwrap(FeatureTable.class);
        if (table != null) {
            return table.getRequiredFeatures();
        }
        return new HashSet<>();
    }
}
