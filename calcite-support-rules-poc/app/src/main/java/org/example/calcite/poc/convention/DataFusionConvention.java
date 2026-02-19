package org.example.calcite.poc.convention;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.rel.RelNode;
import java.util.Set;

/**
 * Convention for the DataFusion engine.
 * This is the fastest engine but has limited functionality - only Feature A.
 * Should only be used in special cases where the query requirements match its capabilities.
 */
public class DataFusionConvention extends Convention.Impl {
    public static final DataFusionConvention INSTANCE = new DataFusionConvention();

    private DataFusionConvention() {
        super("DATAFUSION", RelNode.class);
    }

    public interface DataFusionRel extends RelNode {
    }

    /**
     * Features supported by the DataFusion engine for pushdown.
     * Supports Features A and B.
     */
    public static final Set<String> SUPPORTED_FEATURES = Set.of(
        "FEATURE_A",
        "FEATURE_B"
    );

    public static boolean supportsFeature(String feature) {
        return SUPPORTED_FEATURES.contains(feature);
    }
}
