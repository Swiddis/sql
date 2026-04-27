# PPL Correctness Testing Framework

Property-based testing framework for OpenSearch PPL (Piped Processing Language), using metamorphic relations to find correctness bugs.

## Architecture

```
datagen/     - Generate test indices with schema and data
generators/  - PPL query generators (context-aware)
properties/  - Test properties (TLP, sorting, aggregation invariants)
runner/      - Execution engine and result validation
```

## Testing Strategies

### Ternary Logic Partitioning (TLP)
Split queries by boolean predicate `p`:
- `source=index | where p | ...`
- `source=index | where not p | ...`  
- `source=index | where isnull(p) | ...`

Union should match unfiltered query.

### Aggregation Invariants
- `stats count() by field` total = `stats count()`
- `stats sum(x)` = sum of `stats sum(x) by groupfield`
- Monotonicity: `stats max(x)` >= `stats min(x)`

### Sorting Properties
- `sort field` output must be ordered
- `sort +field | head 1` = `stats max(field)`
- `sort -field | head 1` = `stats min(field)`

### Differential Testing
Compare equivalent query forms:
- `where a=1 | where b=2` vs `where a=1 and b=2`
- `eval x=a+b | where x>5` vs `where a+b>5`
- `stats count() by a,b` vs nested grouping

## Usage

```bash
# Generate test data
python -m ppl_correctness.datagen --indices 10

# Run TLP tests
python -m ppl_correctness.runner --property tlp --iterations 1000

# Run all properties
python -m ppl_correctness.runner --all
```

## Dependencies

- Python 3.11+
- opensearch-py
- hypothesis (property-based testing)
