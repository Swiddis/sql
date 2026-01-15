#!/bin/bash
# Run integration tests with PPL query logging enabled
#
# Usage:
#   ./run-tests-with-logging.sh [output_file] [test_pattern]
#
# Examples:
#   ./run-tests-with-logging.sh queries.jsonl
#   ./run-tests-with-logging.sh queries.jsonl "*CalcitePPL*"
#   ./run-tests-with-logging.sh queries.jsonl "CalciteMathematicalFunctionIT"

set -e

# Default output file
OUTPUT_FILE="${1:-queries.jsonl}"
TEST_PATTERN="${2:-*CalcitePPL*}"

# Get absolute path for output file
OUTPUT_FILE="$(cd "$(dirname "$OUTPUT_FILE")" && pwd)/$(basename "$OUTPUT_FILE")"

echo "================================================"
echo "Running PPL Integration Tests with Query Logging"
echo "================================================"
echo "Output file: $OUTPUT_FILE"
echo "Test pattern: $TEST_PATTERN"
echo ""

# Remove existing output file
rm -f "$OUTPUT_FILE"

# Navigate to project root
cd "$(dirname "$0")/../.."

# Run tests with the logging property
./gradlew :integ-test:integTest \
    --tests "$TEST_PATTERN" \
    -Dppl.query.logger.output="$OUTPUT_FILE" \
    --no-daemon

echo ""
echo "================================================"
echo "Test execution complete!"
echo "================================================"
echo "Logged queries: $(wc -l < "$OUTPUT_FILE" 2>/dev/null || echo 0)"
echo "Output file: $OUTPUT_FILE"
echo ""
echo "Next steps:"
echo "1. Extract test cases: python3 integ-test/scripts/extract-test-cases.py $OUTPUT_FILE output_dir/"
echo "2. Add [data] sections to the generated TOML files"
