# PPL Test Case Extraction

This directory contains tools for extracting PPL test cases from integration tests into a standardized TOML format for cross-platform testing.

## Overview

The system works by:
1. **Logging**: Intercepts PPL queries and responses during integration test execution
2. **Extraction**: Converts the logged data into TOML test case files
3. **Manual enrichment**: Add index mappings and data to complete the test cases

## Quick Start

### Step 1: Run Tests with Logging

```bash
# Run all Calcite PPL tests and log queries
./integ-test/scripts/run-tests-with-logging.sh queries.jsonl

# Run specific test class
./integ-test/scripts/run-tests-with-logging.sh queries.jsonl "CalciteMathematicalFunctionIT"

# Run specific test method
./integ-test/scripts/run-tests-with-logging.sh queries.jsonl "*CalciteMathematicalFunctionIT.testLog*"
```

This will:
- Run the integration tests
- Log all PPL queries and responses to `queries.jsonl`
- Each line in the file is a JSON object with query, response, and metadata

### Step 2: Extract Test Cases to TOML

```bash
# Extract all logged queries into TOML test cases
python3 integ-test/scripts/extract-test-cases.py queries.jsonl test-cases/

# This creates one .toml file per query
```

### Step 3: Add Index Data

The generated TOML files have a placeholder for the `[data]` section:

```toml
# TODO: Add index mapping and data
# [data]
# mapping = { "key": "long" }
# index = [
#     { "key": 0 },
#     { "key": 1 }
# ]
```

You need to:
1. Identify which index the test uses (look at the query)
2. Extract the mapping from the test setup code
3. Extract the index data from the test data files

## Manual Approach: Direct Gradle

If you want more control, you can run tests directly with Gradle:

```bash
# Set the output file via system property
./gradlew :integ-test:integTest \
    --tests "CalciteMathematicalFunctionIT" \
    -Dppl.query.logger.output="$PWD/queries.jsonl"
```

## How It Works

### 1. PPLQueryLogger

The `PPLQueryLogger` class intercepts query execution in `PPLIntegTestCase`:
- Watches for PPL query requests (`/_plugins/_ppl`)
- Captures the query string and full response
- Logs to a JSON Lines file (one JSON object per line)

Log entry format:
```json
{
  "timestamp": "2026-01-15T12:34:56Z",
  "endpoint": "/_plugins/_ppl",
  "query": "source = index | eval result = SIN(key) | fields result",
  "status_code": 200,
  "format": "json",
  "schema": [...],
  "datarows": [...]
}
```

### 2. Extract Script

The Python script (`extract-test-cases.py`):
- Reads the JSON Lines log file
- Converts each entry to a TOML file
- Infers types from the schema
- Formats rows as dictionaries

Generated TOML structure:
```toml
[data]
index = "opensearch-sql_test_index_calcs"

[query]
language = "ppl"
query = '''
source = opensearch-sql_test_index_calcs | eval result = SIN(num0) | fields result
'''

[result]
types = {
    "result" = "double"
}
rows = [
    { "result" = 0.0 },
    { "result" = 0.8414709848078965 },
]
```

## Tips & Tricks

### Filter Specific Tests

```bash
# Only mathematical function tests
./integ-test/scripts/run-tests-with-logging.sh math_queries.jsonl "*MathematicalFunction*"

# Only datetime tests
./integ-test/scripts/run-tests-with-logging.sh datetime_queries.jsonl "*DateTime*"

# Single test method
./integ-test/scripts/run-tests-with-logging.sh sin_test.jsonl "*MathematicalFunctionIT.testSin"
```

### Batch Processing

```bash
# Extract multiple test classes separately
for test in CalciteMathematicalFunctionIT CalciteDateTimeFunctionIT; do
    ./integ-test/scripts/run-tests-with-logging.sh "${test}.jsonl" "$test"
    python3 integ-test/scripts/extract-test-cases.py "${test}.jsonl" "test-cases/${test}/"
done
```

### Debugging

Check if logging is working:
```bash
# Run a single test and check output
./gradlew :integ-test:integTest \
    --tests "CalciteMathematicalFunctionIT.testSin" \
    -Dppl.query.logger.output="$PWD/debug.jsonl"

# View the log
cat debug.jsonl | jq .
```

## Index Reference System

The extraction script automatically adds an `[data]` section with the index name:

```toml
[data]
index = "opensearch-sql_test_index_calcs"
```

### How It Works

1. **Index name extraction**: The script parses `source=index_name` from the PPL query
2. **Reference generation**: Creates a simple reference in the TOML file
3. **Lookup file**: `index-lookup.json` maps index names to their resource files

### Using the Reference in Your Test Framework

```python
# Pseudocode for your test runner
import json
import toml

# 1. Load the test case
test_case = toml.load("test-cases/0001_sin_function.toml")
index_name = test_case["data"]["index"]

# 2. Look up the resource files
with open("index-lookup.json") as f:
    lookup = json.load(f)

index_info = lookup["indices"][index_name]
mapping_file = index_info["mapping"]
data_file = index_info["data"]

# 3. Load the index definition
mapping = json.load(open(mapping_file))
documents = [json.loads(line) for line in open(data_file)]

# 4. Create index and run test
backend.create_index(index_name, mapping)
backend.load_documents(index_name, documents)
result = backend.execute_query(test_case["query"]["query"])

# 5. Verify results
assert result.rows == test_case["result"]["rows"]
```

### Resource File Locations

All index resource files are in `integ-test/src/test/resources/`:

- **Mapping files**: `*_index_mapping.json` or `*_index_mappings.json`
- **Data files**: `*.json` (NDJSON format - one document per line)

Example:
```
integ-test/src/test/resources/
├── calcs_index_mappings.json  # mapping
├── calcs.json                  # data (NDJSON)
├── accounts.json               # data
├── ...
```

### Adding New Indices to the Lookup

If you find an index not in `index-lookup.json`:

1. Find it in `SQLIntegTestCase.Index` enum:
   ```bash
   grep -A5 "opensearch-sql_test_index_your_index" \
       integ-test/src/test/java/org/opensearch/sql/legacy/SQLIntegTestCase.java
   ```

2. Add it to `index-lookup.json`:
   ```json
   "opensearch-sql_test_index_your_index": {
     "mapping": "integ-test/src/test/resources/your_index_mappings.json",
     "data": "integ-test/src/test/resources/your_index.json",
     "enum": "YOUR_INDEX"
   }
   ```

## Limitations

- Only captures successful queries (200 OK responses)
- CSV responses are logged but not fully parsed
- Doesn't capture index creation/deletion
- Doesn't handle multi-index queries yet
- Manual work needed to add [data] section

## Future Enhancements

Possible improvements:
1. Auto-detect which index each query uses
2. Auto-extract index mappings and data
3. Deduplicate similar queries
4. Group test cases by feature
5. Capture explain plans for reference
6. Handle error test cases

## Examples

See the generated TOML files for the expected format. Your final test runner should be able to:

```rust
// Pseudocode
let test_case = load_toml("test-cases/0001_sin_function.toml");

// Create index with mapping and data
backend.create_index(test_case.data.mapping);
backend.load_data(test_case.data.index);

// Run query
let result = backend.execute(test_case.query.query);

// Verify result
assert_eq!(result.schema_types(), test_case.result.types);
assert_eq!(result.rows(), test_case.result.rows);
```

## Contributing

To add support for other query types (SQL, etc.), modify:
- `PPLQueryLogger.shouldLog()` - add endpoint detection
- `extract-test-cases.py` - handle different response formats
