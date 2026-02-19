package org.example.calcite.poc;

import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.*;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;

import java.util.Properties;
import org.example.calcite.poc.convention.DataFusionConvention;
import org.example.calcite.poc.convention.DslConvention;
import org.example.calcite.poc.convention.IndexScanConvention;
import org.example.calcite.poc.rel.DataFusionTableScan;
import org.example.calcite.poc.rel.DslTableScan;
import org.example.calcite.poc.rel.IndexScanTableScan;
import org.example.calcite.poc.rule.DataFusionConverterRule;
import org.example.calcite.poc.rule.DslConverterRule;
import org.example.calcite.poc.rule.IndexScanConverterRule;
import org.example.calcite.poc.table.FeatureRelOptTable;
import org.example.calcite.poc.table.FeatureTable;
import org.example.calcite.poc.table.SimpleSchema;

import java.util.List;
import java.util.Set;

/**
 * Proof of Concept demonstrating Apache Calcite's cost-based optimization
 * across three execution engines with different capabilities:
 *
 * 1. DataFusion - fastest but limited (only Feature A)
 * 2. DSL - medium speed, good feature support (Features A, B, C)
 * 3. IndexScan - slowest but universal (supports everything via postprocessing)
 */
public class CalcitePOC {

    private final RelDataTypeFactory typeFactory;

    public CalcitePOC() {
        // Create type factory
        this.typeFactory = new org.apache.calcite.jdbc.JavaTypeFactoryImpl();
    }

    /**
     * Run the POC with various test scenarios.
     */
    public void run() {
        System.out.println("=".repeat(80));
        System.out.println("Apache Calcite Multi-Engine Optimization POC");
        System.out.println("=".repeat(80));
        System.out.println();

        printEngineCapabilities();
        System.out.println();

        // Test Case 1: Query requiring only Feature A
        // Expected: DataFusion (fastest and both engines support A)
        testScenario(
            "Test 1: Query with Feature A only",
            Set.of("FEATURE_A"),
            "DataFusion should be chosen (fastest, both engines support A)"
        );

        // Test Case 2: Query requiring only Feature B
        // Expected: DataFusion (only DataFusion supports B)
        testScenario(
            "Test 2: Query with Feature B only",
            Set.of("FEATURE_B"),
            "DataFusion should be chosen (only DataFusion supports B)"
        );

        // Test Case 3: Query requiring only Feature C
        // Expected: DSL (only DSL supports C)
        testScenario(
            "Test 3: Query with Feature C only",
            Set.of("FEATURE_C"),
            "DSL should be chosen (only DSL supports C)"
        );

        // Test Case 4: Query requiring Features A and B
        // Expected: DataFusion (supports both A and B)
        testScenario(
            "Test 4: Query with Features A and B",
            Set.of("FEATURE_A", "FEATURE_B"),
            "DataFusion should be chosen (supports both A and B)"
        );

        // Test Case 5: Query requiring Features A and C
        // Expected: DSL (supports both A and C)
        testScenario(
            "Test 5: Query with Features A and C",
            Set.of("FEATURE_A", "FEATURE_C"),
            "DSL should be chosen (supports both A and C)"
        );

        // Test Case 6: Query requiring Features B and C
        // Expected: IndexScan (neither DataFusion nor DSL support both)
        testScenario(
            "Test 6: Query with Features B and C",
            Set.of("FEATURE_B", "FEATURE_C"),
            "IndexScan should be chosen (no fast engine supports both B and C)"
        );

        // Test Case 7: Query requiring all features A, B, and C
        // Expected: IndexScan (no fast engine supports all three)
        testScenario(
            "Test 7: Query with Features A, B, and C",
            Set.of("FEATURE_A", "FEATURE_B", "FEATURE_C"),
            "IndexScan should be chosen (no fast engine supports all three)"
        );

        // Test Case 8: No features required
        // Expected: DataFusion (fastest and no features needed)
        testScenario(
            "Test 8: Query with no special features",
            Set.of(),
            "DataFusion should be chosen (fastest, no features required)"
        );

        System.out.println("=".repeat(80));
        System.out.println("POC Complete!");
        System.out.println("=".repeat(80));
    }

    private void printEngineCapabilities() {
        System.out.println("Engine Capabilities:");
        System.out.println("-".repeat(80));
        System.out.println("1. DataFusion: Fastest (cost=1x)  | Features: A, B");
        System.out.println("2. DSL:        Medium  (cost=10x) | Features: A, C");
        System.out.println("3. IndexScan:  Slowest (cost=100x)| Features: All (via postprocessing)");
        System.out.println("-".repeat(80));
    }

    private void testScenario(String title, Set<String> requiredFeatures, String expectedOutcome) {
        System.out.println("\n" + title);
        System.out.println("-".repeat(80));
        System.out.println("Required features: " + (requiredFeatures.isEmpty() ? "None" : requiredFeatures));
        System.out.println("Expected: " + expectedOutcome);
        System.out.println();

        try {
            // Create a table with the required features
            RelDataType rowType = createRowType();
            FeatureTable table = new FeatureTable("test_table", requiredFeatures, rowType, 10000.0);

            // Create a new planner for this test
            VolcanoPlanner planner = new VolcanoPlanner();
            planner.addRelTraitDef(ConventionTraitDef.INSTANCE);

            // Register converter rules
            planner.addRule(DataFusionConverterRule.INSTANCE);
            planner.addRule(DslConverterRule.INSTANCE);
            planner.addRule(IndexScanConverterRule.INSTANCE);

            // Create cluster
            RexBuilder rexBuilder = new RexBuilder(typeFactory);
            RelOptCluster cluster = RelOptCluster.create(planner, rexBuilder);

            // Create table reference
            SimpleSchema relOptSchema = new SimpleSchema();
            FeatureRelOptTable relOptTable = new FeatureRelOptTable(
                relOptSchema,
                rowType,
                List.of("test_table"),
                table
            );

            // Create logical scan
            RelNode logicalPlan = org.apache.calcite.rel.logical.LogicalTableScan.create(
                cluster,
                relOptTable,
                List.of()
            );

            // Register the logical plan
            planner.setRoot(logicalPlan);

            // Request all three physical conventions and let the planner pick the cheapest
            // We'll try each convention and see which one the planner can produce
            RelNode bestPlan = null;
            double bestCost = Double.MAX_VALUE;
            String bestConvention = null;

            // Try each convention and pick the one with lowest cost
            for (Convention targetConvention : List.of(
                    DataFusionConvention.INSTANCE,
                    DslConvention.INSTANCE,
                    IndexScanConvention.INSTANCE)) {
                try {
                    RelTraitSet targetTraits = cluster.traitSet().replace(targetConvention);
                    RelNode convertedPlan = planner.changeTraits(logicalPlan, targetTraits);

                    if (convertedPlan != null) {
                        planner.setRoot(convertedPlan);
                        RelNode candidatePlan = planner.findBestExp();

                        RelOptCost cost = planner.getCost(candidatePlan, cluster.getMetadataQuery());
                        double costValue = cost.isInfinite() ? Double.MAX_VALUE :
                            cost.getRows() * cost.getCpu();

                        if (costValue < bestCost) {
                            bestCost = costValue;
                            bestPlan = candidatePlan;
                            bestConvention = targetConvention.getName();
                        }
                    }
                } catch (Exception e) {
                    // This convention couldn't produce a plan, continue
                }
            }

            if (bestPlan == null) {
                throw new RuntimeException("No physical plan could be produced");
            }

            // Display results
            System.out.println("Optimized Plan:");
            System.out.println(bestPlan.explain());

            String chosenEngine = getEngineType(bestPlan);
            System.out.println("Chosen Engine: " + chosenEngine);

            RelOptCost cost = planner.getCost(bestPlan, cluster.getMetadataQuery());
            System.out.println("Estimated Cost: " + cost);

        } catch (Exception e) {
            System.out.println("Error during optimization: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println();
    }

    private String getEngineType(RelNode node) {
        if (node instanceof DataFusionTableScan) {
            return "DataFusion (fastest)";
        } else if (node instanceof DslTableScan) {
            return "DSL (medium)";
        } else if (node instanceof IndexScanTableScan) {
            return "IndexScan (slowest, universal)";
        }
        return "Unknown";
    }

    private RelDataType createRowType() {
        return typeFactory.builder()
            .add("id", SqlTypeName.INTEGER)
            .add("name", SqlTypeName.VARCHAR)
            .add("value", SqlTypeName.DOUBLE)
            .build();
    }

    public static void main(String[] args) {
        CalcitePOC poc = new CalcitePOC();
        poc.run();
    }
}
