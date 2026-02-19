# Apache Calcite Multi-Engine Optimization POC

This project demonstrates Apache Calcite's cost-based optimizer working across three execution engines with different capabilities and performance characteristics.

## Execution Engines

The POC models three execution engines with overlapping but different capabilities:

1. **DataFusion** - Fastest (cost factor: 1x)
   - Feature support: A and B
   - Best for queries using features A and/or B

2. **DSL** - Medium speed (cost factor: 10x)
   - Feature support: A and C
   - Best for queries using features A and/or C

3. **IndexScan** - Slowest (cost factor: 100x)
   - Universal support: All features via postprocessing
   - Fallback when other engines can't handle the query

**Key insight**: The two fast engines have overlapping but different capabilities. Feature A works on both, but B is DataFusion-only and C is DSL-only. Queries needing both B and C must fall back to IndexScan.

## How It Works

The optimizer uses:

- **Convention-based architecture**: Each engine is represented as a Calcite Convention
- **Converter rules**: Rules that convert logical plans to physical plans for each engine
- **Feature validation**: Converter rules check if their engine supports required features
- **Cost-based optimization**: Calcite's VolcanoPlanner selects the engine with lowest cost that can handle the query

## Test Scenarios

The POC runs eight test scenarios showing the optimizer's decision-making:

### Test 1: Feature A only
- **Expected**: DataFusion (fastest, both support A)
- **Result**: DataFusion chosen ✓

### Test 2: Feature B only
- **Expected**: DataFusion (only DataFusion supports B)
- **Result**: DataFusion chosen ✓

### Test 3: Feature C only
- **Expected**: DSL (only DSL supports C)
- **Result**: DSL chosen ✓

### Test 4: Features A and B
- **Expected**: DataFusion (supports both)
- **Result**: DataFusion chosen ✓

### Test 5: Features A and C
- **Expected**: DSL (supports both)
- **Result**: DSL chosen ✓

### Test 6: Features B and C
- **Expected**: IndexScan (no fast engine supports both)
- **Result**: IndexScan chosen ✓

### Test 7: Features A, B, and C
- **Expected**: IndexScan (no fast engine supports all three)
- **Result**: IndexScan chosen ✓

### Test 8: No features
- **Expected**: DataFusion (fastest, no requirements)
- **Result**: DataFusion chosen ✓

## Running the POC

```bash
./gradlew run
```

## Project Structure

```
app/src/main/java/org/example/calcite/poc/
├── convention/          # Convention definitions for each engine
│   ├── DataFusionConvention.java
│   ├── DslConvention.java
│   └── IndexScanConvention.java
├── rel/                 # Physical relation implementations
│   ├── DataFusionTableScan.java
│   ├── DslTableScan.java
│   └── IndexScanTableScan.java
├── rule/                # Converter rules
│   ├── DataFusionConverterRule.java
│   ├── DslConverterRule.java
│   └── IndexScanConverterRule.java
├── table/               # Table and schema implementations
│   ├── FeatureTable.java
│   ├── FeatureRelOptTable.java
│   └── SimpleSchema.java
└── CalcitePOC.java      # Main demo application
```

## Key Concepts Demonstrated

1. **Cost Model**: Each engine has different costs, allowing Calcite to choose the most efficient option
2. **Pushdown Capabilities**: Engines declare which features they support
3. **Rule-based Conversion**: Converter rules validate feature support before creating physical plans
4. **Fallback Strategy**: IndexScan acts as a universal fallback when specialized engines can't handle a query

## Dependencies

- Apache Calcite 1.41.0
- Java 25
- Gradle 9.3.1
