#!/usr/bin/env python3
"""
Convert PPL query logs (JSON Lines format) to JSON test case format.

Usage:
    python3 extract-test-cases.py queries.jsonl output_dir/

This script reads the query log file and generates individual JSON test case files.
You'll need to manually add the data section with index mappings and data.
"""

import json
import sys
from pathlib import Path
import re


def sanitize_filename(query):
    """Generate a safe filename from a query string."""
    # Take first 50 chars, remove special chars
    name = re.sub(r'[^\w\s-]', '', query[:50])
    name = re.sub(r'[-\s]+', '_', name).strip('_')
    return name.lower()


def infer_types_from_schema(schema):
    """Convert OpenSearch schema to simple type names."""
    type_map = {}
    for field in schema:
        name = field.get("name", "")
        os_type = field.get("type", "unknown")

        # Map OpenSearch types to simpler type names
        type_mapping = {
            "integer": "integer",
            "long": "long",
            "double": "double",
            "float": "float",
            "boolean": "boolean",
            "keyword": "string",
            "text": "string",
            "date": "timestamp",
        }

        simple_type = type_mapping.get(os_type.lower(), os_type)
        type_map[name] = simple_type

    return type_map


def convert_datarows_to_dict(datarows, schema):
    """Convert datarows array format to dict format with field names."""
    field_names = [field.get("name", f"col{i}") for i, field in enumerate(schema)]

    result_rows = []
    for row in datarows:
        row_dict = {}
        for i, value in enumerate(row):
            if i < len(field_names):
                row_dict[field_names[i]] = value
        result_rows.append(row_dict)

    return result_rows


def extract_index_name(query):
    """Extract the index name from a PPL query."""
    # Match: source = index_name or source=index_name
    match = re.search(r'source\s*=\s*([^\s|]+)', query, re.IGNORECASE)
    if match:
        return match.group(1).strip()
    return None


def process_log_entry(entry, output_dir, counter):
    """Process a single log entry and create a JSON file."""
    query = entry.get("query", "")

    if not query:
        return

    # Extract index name from query
    index_name = extract_index_name(query)

    # Generate filename
    base_name = sanitize_filename(query)
    filename = f"{counter:04d}_{base_name}.json"
    output_path = output_dir / filename

    # Extract schema and data - they may be JSON strings
    schema_data = entry.get("schema", [])
    datarows_data = entry.get("datarows", [])

    # Parse if they're JSON strings
    if isinstance(schema_data, str):
        schema = json.loads(schema_data)
    else:
        schema = schema_data

    if isinstance(datarows_data, str):
        datarows = json.loads(datarows_data)
    else:
        datarows = datarows_data

    if not datarows:
        print(f"Skipping {filename}: no datarows")
        return

    # Convert to result format
    types = infer_types_from_schema(schema)
    rows = convert_datarows_to_dict(datarows, schema)

    # Build JSON structure
    test_case = {
        "data": {},
        "query": {
            "language": "ppl",
            "query": query
        },
        "result": {
            "types": types,
            "rows": rows
        }
    }

    # Add index name if detected
    if index_name:
        test_case["data"]["index"] = index_name

    # Write file
    with open(output_path, 'w') as f:
        json.dump(test_case, f, indent=2)

    print(f"Created: {filename}")


def main():
    if len(sys.argv) != 3:
        print("Usage: python3 extract-test-cases.py queries.jsonl output_dir/")
        sys.exit(1)

    input_file = Path(sys.argv[1])
    output_dir = Path(sys.argv[2])

    if not input_file.exists():
        print(f"Error: Input file {input_file} does not exist")
        sys.exit(1)

    output_dir.mkdir(parents=True, exist_ok=True)

    # Process each line in the JSONL file
    counter = 1
    with open(input_file, 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue

            try:
                entry = json.loads(line)
                process_log_entry(entry, output_dir, counter)
                counter += 1
            except json.JSONDecodeError as e:
                print(f"Warning: Failed to parse line: {e}")
                continue

    print(f"\nProcessed {counter - 1} queries")
    print(f"Output directory: {output_dir}")
    print("\nNext steps:")
    print("1. Review generated JSON files")
    print("2. Add data sections with index mappings")
    print("3. Use index loading utils to capture actual index data")


if __name__ == "__main__":
    main()
