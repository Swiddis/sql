package org.example.calcite.poc.convention;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.rel.RelNode;

/**
 * Convention for the IndexScan engine.
 * This is the slowest engine that supports no pushdown features natively,
 * but can postprocess everything universally.
 */
public class IndexScanConvention extends Convention.Impl {
    public static final IndexScanConvention INSTANCE = new IndexScanConvention();

    private IndexScanConvention() {
        super("INDEXSCAN", RelNode.class);
    }

    public interface IndexScanRel extends RelNode {
    }
}
