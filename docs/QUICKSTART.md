# Quick Start Guide

## Prerequisites

1. Python 3.11+
2. Running OpenSearch cluster (default: localhost:9200)

## Setup

```bash
# Install dependencies
uv pip install -e .

# Or with pip
pip install -e .
```

## Start OpenSearch (Docker)

```bash
docker run -d \
  -p 9200:9200 \
  -p 9600:9600 \
  -e "discovery.type=single-node" \
  -e "OPENSEARCH_INITIAL_ADMIN_PASSWORD=Admin123!" \
  opensearchproject/opensearch:latest
```

## Run Tests

```bash
# Run TLP (Ternary Logic Partitioning) tests
python -m ppl_correctness.main --property tlp --iterations 50

# Run sorting invariant tests
python -m ppl_correctness.main --property sort --iterations 50

# Run aggregation conservation tests
python -m ppl_correctness.main --property aggregation --iterations 50

# Run all properties
python -m ppl_correctness.main --all --iterations 100

# Reproducible runs with seed
python -m ppl_correctness.main --all --iterations 100 --seed 42
```

## Example Output

```
2026-04-27 14:30:00 [INFO] Generating 5 test contexts...
2026-04-27 14:30:02 [INFO] Running tlp property tests (100 iterations)...
2026-04-27 14:30:45 [INFO] ============================================================
2026-04-27 14:30:45 [INFO] Results: 98/100 passed
2026-04-27 14:30:45 [ERROR] Found 2 failures:
2026-04-27 14:30:45 [ERROR]   TernaryLogicPartitioning violated: TLP partition count mismatch: 95 != 100
  Query: Predicate: field_2 > 50 AND field_3 = 'blue'
  Expected: 100
  Actual: 95
```

## What Gets Tested

### TLP (Ternary Logic Partitioning)
For any query and boolean predicate, the three partitions (TRUE, FALSE, NULL) must:
- Cover all rows (no missing data)
- Be disjoint (no overlapping rows)
- Sum to total row count

**Finds bugs in:**
- NULL handling in WHERE clauses
- Boolean logic evaluation
- Predicate negation

### Sorting Invariants
Sorted results must be in order, and sort+head should equal min/max aggregations.

**Finds bugs in:**
- Sort implementation
- Collation/comparison operators
- Head/tail with sorting

### Aggregation Conservation
Grouped aggregations must sum to ungrouped totals.

**Finds bugs in:**
- GROUP BY mechanics
- Aggregation pushdown
- COUNT/SUM calculations

## Adding New Properties

See `ppl_correctness/properties/` for examples. Implement the `Property` interface:

```python
from ppl_correctness.properties.base import Property, PropertyViolation

class MyProperty(Property):
    @property
    def name(self) -> str:
        return "MyProperty"

    def check(self, context: IndexContext, client: OpenSearch):
        # Generate queries
        # Execute and compare results
        # Return list of PropertyViolation if bugs found
        return []
```

Register in `main.py` and run with `--property my_property`.

## Architecture

```
┌─────────────┐
│  main.py    │  Entry point, CLI args
└──────┬──────┘
       │
       ├─> datagen/context.py      Generate test indices
       ├─> generators/ppl.py       Generate PPL queries
       ├─> properties/*.py         Define test properties
       └─> runner/executor.py      Execute tests, collect results
```

## Tips

- Start with `--iterations 10` for fast feedback
- Use `--seed` for reproducible failure investigation
- TLP is the most powerful property (found most bugs in old framework)
- Add `--indices 20` for more schema diversity
- Export `OPENSEARCH_HOST=https://remote:9200` for remote clusters
