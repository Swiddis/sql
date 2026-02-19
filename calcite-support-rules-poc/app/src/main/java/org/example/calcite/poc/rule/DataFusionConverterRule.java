package org.example.calcite.poc.rule;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.example.calcite.poc.convention.DataFusionConvention;
import org.example.calcite.poc.rel.DataFusionTableScan;
import org.example.calcite.poc.table.FeatureRelOptTable;
import org.example.calcite.poc.table.FeatureTable;

import java.util.HashSet;
import java.util.Set;

/**
 * Converter rule from logical table scan to DataFusion engine.
 * This succeeds only if all required features are supported by DataFusion.
 * DataFusion is the fastest but most limited engine.
 */
public class DataFusionConverterRule extends ConverterRule {

    public static final DataFusionConverterRule INSTANCE = new DataFusionConverterRule();

    private DataFusionConverterRule() {
        super(
            LogicalTableScan.class,
            Convention.NONE,
            DataFusionConvention.INSTANCE,
            "DataFusionConverterRule"
        );
    }

    @Override
    public RelNode convert(RelNode rel) {
        LogicalTableScan scan = (LogicalTableScan) rel;

        // Get required features from the table
        Set<String> requiredFeatures = getRequiredFeatures(scan);

        // Check if DataFusion supports all required features
        for (String feature : requiredFeatures) {
            if (!DataFusionConvention.supportsFeature(feature)) {
                return null; // DataFusion cannot handle this query
            }
        }

        RelTraitSet traitSet = scan.getTraitSet().replace(DataFusionConvention.INSTANCE);

        return new DataFusionTableScan(
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
