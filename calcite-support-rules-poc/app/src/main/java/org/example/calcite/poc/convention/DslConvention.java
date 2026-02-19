package org.example.calcite.poc.convention;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.rel.RelNode;
import java.util.Set;

/**
 * Convention for the DSL engine.
 * This is the middle-tier engine that supports many pushdown features
 * including Feature A, Feature B, and Feature C.
 */
public class DslConvention extends Convention.Impl {
    public static final DslConvention INSTANCE = new DslConvention();

    private DslConvention() {
        super("DSL", RelNode.class);
    }

    public interface DslRel extends RelNode {
    }

    /**
     * Features supported by the DSL engine for pushdown.
     * Supports Features A and C.
     */
    public static final Set<String> SUPPORTED_FEATURES = Set.of(
        "FEATURE_A",
        "FEATURE_C"
    );

    public static boolean supportsFeature(String feature) {
        return SUPPORTED_FEATURES.contains(feature);
    }
}
