#!/usr/bin/env python3
"""
Generate an index reference file from SQLIntegTestCase.Index enum.

This creates a JSON file mapping index names to their resource files (mapping and data).
Your test framework can use this to load the actual index definitions.

Usage:
    python3 generate-index-reference.py > index-reference.json
"""

import json
import re
import sys
from pathlib import Path


def parse_index_enum(java_file_path):
    """Parse the Index enum from SQLIntegTestCase.java."""
    with open(java_file_path, 'r') as f:
        content = f.read()

    # Find the Index enum definition
    enum_match = re.search(r'public enum Index \{(.*?)\n  \}', content, re.DOTALL)
    if not enum_match:
        print("Error: Could not find Index enum", file=sys.stderr)
        return {}

    enum_body = enum_match.group(1)

    # Parse each enum entry
    # Pattern: NAME("index_name", "type", mapping, "data_path")
    pattern = r'(\w+)\(\s*"([^"]+)",\s*"([^"]+)",\s*([^,]+),\s*"([^"]+)"\)'

    indices = {}
    for match in re.finditer(pattern, enum_body):
        enum_name = match.group(1)
        index_name = match.group(2)
        index_type = match.group(3)
        mapping_expr = match.group(4).strip()
        data_path = match.group(5)

        # Extract mapping file if it's a method call like getMappingFile("...")
        mapping_file = None
        if 'getMappingFile' in mapping_expr or 'getTpchMappingFile' in mapping_expr or 'getBig5MappingFile' in mapping_expr or 'getClickBenchMappingFile' in mapping_expr:
            file_match = re.search(r'"([^"]+)"', mapping_expr)
            if file_match:
                mapping_file = f"integ-test/src/test/resources/{file_match.group(1)}"
        elif mapping_expr == 'null':
            mapping_file = None
        else:
            # It's a method like getAccountIndexMapping()
            mapping_file = f"defined in SQLIntegTestCase.{mapping_expr}"

        indices[index_name] = {
            "enum_name": enum_name,
            "type": index_type,
            "mapping": mapping_file,
            "data": data_path
        }

    return indices


def main():
    # Find the SQLIntegTestCase.java file
    sql_integ_test_case = Path("integ-test/src/test/java/org/opensearch/sql/legacy/SQLIntegTestCase.java")

    if not sql_integ_test_case.exists():
        print("Error: SQLIntegTestCase.java not found", file=sys.stderr)
        print(f"Current directory: {Path.cwd()}", file=sys.stderr)
        sys.exit(1)

    indices = parse_index_enum(sql_integ_test_case)

    # Output as JSON
    output = {
        "description": "Index reference mapping from index names to their resource files",
        "source": "Generated from SQLIntegTestCase.Index enum",
        "indices": indices
    }

    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
