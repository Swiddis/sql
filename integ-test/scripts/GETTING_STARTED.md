# Getting Started: Extracting Test Cases

This is a quick start guide to extract PPL test cases from integration tests.

## TL;DR

```bash
cd /workplace/sawiddis/sql

# 1. Run tests with logging enabled
./integ-test/scripts/run-tests-with-logging.sh queries.jsonl "CalciteMathematicalFunctionIT"

# 2. Check what was captured
python3 integ-test/scripts/analyze-queries.py queries.jsonl

# 3. Extract to TOML files
python3 integ-test/scripts/extract-test-cases.py queries.jsonl test-cases/

# 4. Review the files
ls -l test-cases/
```

## What You Get

Each captured query becomes a TOML file like this:

```toml
# test-cases/0001_sin_function.toml

[data]
index = "opensearch-sql_test_index_calcs"

[query]
language = "ppl"
query = '''
source = calcs | eval result = SIN(num0) | fields result
'''

[result]
types = {
    "result" = "double"
}
rows = [
    { "result" = 0.0 },
    { "result" = 0.8414709848078965 },
    { "result" = 0.9092974268256817 },
]
```

## Understanding the Index Reference

The `[data]` section now contains just an index name reference:

```toml
[data]
index = "opensearch-sql_test_index_calcs"
```

### How Your Test Framework Uses This

Your test framework can:

1. **Read the index name** from the `[data]` section
2. **Look up the resource files** in `integ-test/scripts/index-lookup.json`
3. **Load the mapping and data** from the resource files

Example lookup:
```json
{
  "opensearch-sql_test_index_calcs": {
    "mapping": "integ-test/src/test/resources/calcs_index_mappings.json",
    "data": "integ-test/src/test/resources/calcs.json",
    "enum": "CALCS"
  }
}
```

### Resource File Formats

- **Mapping files**: Standard OpenSearch mapping JSON
  ```json
  {
    "mappings": {
      "properties": {
        "num0": {"type": "double"},
        "num1": {"type": "double"}
      }
    }
  }
  ```

- **Data files**: NDJSON (one JSON document per line)
  ```json
  {"num0": 1.5, "num1": 2.0}
  {"num0": 0.0, "num1": 1.0}
  ```

### Finding New Indices

If you encounter an index not in `index-lookup.json`:

1. Search for it in `SQLIntegTestCase.Index` enum:
   ```bash
   grep -A5 "\"$INDEX_NAME\"" integ-test/src/test/java/org/opensearch/sql/legacy/SQLIntegTestCase.java
   ```

2. The enum entry shows the mapping and data file paths

3. Add it to `index-lookup.json` for future reference

## Common Test Patterns

```bash
# All math functions
./integ-test/scripts/run-tests-with-logging.sh math.jsonl "*MathematicalFunction*"

# All datetime functions
./integ-test/scripts/run-tests-with-logging.sh datetime.jsonl "*DateTime*"

# All text functions
./integ-test/scripts/run-tests-with-logging.sh text.jsonl "*TextFunction*"

# All PPL commands
./integ-test/scripts/run-tests-with-logging.sh ppl_commands.jsonl "*Command*"

# Everything (will take a while!)
./integ-test/scripts/run-tests-with-logging.sh all_queries.jsonl "*CalcitePPL*"
```

## Troubleshooting

### No queries logged

Check that:
1. The tests actually executed (check Gradle output)
2. The tests call `executeQuery()` or `executeQueryToString()`
3. The system property is being passed correctly

Debug with:
```bash
./gradlew :integ-test:integTest \
    --tests "CalciteMathematicalFunctionIT.testSin" \
    -Dppl.query.logger.output="$PWD/debug.jsonl" \
    --info
```

### Parsing errors

If `extract-test-cases.py` fails:
1. Check the JSON Lines file is valid: `jq . < queries.jsonl`
2. Look for entries with missing fields
3. Check for CSV responses (they're handled but might not parse perfectly)

### Finding test data

Test data files are in `integ-test/src/test/resources/`:
```bash
ls integ-test/src/test/resources/*.json
```

Mapping files are typically named `*_mapping.json` or `*_index_mappings.json`.

## Output Format Details

The TOML format is designed for your cross-platform test runner:

```toml
[data]
# Index schema
mapping = { "field1": "type1", "field2": "type2" }

# Index documents
index = [
    { "field1": value1, "field2": value2 },
    { "field1": value3, "field2": value4 },
]

[query]
language = "ppl"
query = "source = $INDEX | commands..."

[result]
# Expected result schema
types = { "col1": "type1", "col2": "type2" }

# Expected result rows
rows = [
    { "col1": val1, "col2": val2 },
    { "col1": val3, "col2": val4 },
]
```

## Tips

- Start with a single test class to validate the workflow
- Use `analyze-queries.py` to see what you captured before extracting
- The logger only captures successful queries (status 200)
- CSV format queries are logged but less structured
- You can run the same tests multiple times - the log file is appended to

## Full Workflow Example

```bash
# 1. Pick a test class
TEST_CLASS="CalciteMathematicalFunctionIT"

# 2. Run tests with logging
./integ-test/scripts/run-tests-with-logging.sh "${TEST_CLASS}.jsonl" "$TEST_CLASS"

# 3. Analyze what we got
python3 integ-test/scripts/analyze-queries.py "${TEST_CLASS}.jsonl"

# 4. Extract to TOML
python3 integ-test/scripts/extract-test-cases.py \
    "${TEST_CLASS}.jsonl" \
    "test-cases/${TEST_CLASS}/"

# 5. Check output
ls -lh "test-cases/${TEST_CLASS}/"
head "test-cases/${TEST_CLASS}/0001_"*

# 6. Add [data] sections manually
# ... edit the files to add index mappings and data ...

# 7. Test with your runner
# your-test-runner run test-cases/${TEST_CLASS}/
```

## Questions?

See `README.md` in this directory for full documentation.
