#!/usr/bin/env python3
"""
Analyze PPL query log files and show statistics.

Usage:
    python3 analyze-queries.py queries.jsonl
"""

import json
import sys
from pathlib import Path
from collections import Counter, defaultdict
import re


def extract_ppl_command(query):
    """Extract the main PPL command from a query."""
    # Simple heuristic: first command after 'source ='
    match = re.search(r'source\s*=\s*\S+\s*\|\s*(\w+)', query, re.IGNORECASE)
    if match:
        return match.group(1).lower()

    # Check if it's just a source command
    if re.match(r'^\s*source\s*=', query, re.IGNORECASE):
        return 'source'

    return 'unknown'


def analyze_log_file(input_file):
    """Analyze the log file and print statistics."""
    total_queries = 0
    successful_queries = 0
    failed_queries = 0
    command_counts = Counter()
    query_lengths = []
    response_sizes = []

    with open(input_file, 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue

            try:
                entry = json.loads(line)
                total_queries += 1

                query = entry.get("query", "")
                status = entry.get("status_code", 0)

                if status == 200:
                    successful_queries += 1
                else:
                    failed_queries += 1

                # Extract command
                command = extract_ppl_command(query)
                command_counts[command] += 1

                # Track lengths
                query_lengths.append(len(query))

                # Track response sizes
                datarows = entry.get("datarows", [])
                if datarows:
                    response_sizes.append(len(datarows))

            except json.JSONDecodeError as e:
                print(f"Warning: Failed to parse line: {e}", file=sys.stderr)
                continue

    # Print statistics
    print("=" * 60)
    print("PPL Query Log Analysis")
    print("=" * 60)
    print(f"\nTotal queries:       {total_queries}")
    print(f"Successful (200):    {successful_queries}")
    print(f"Failed (non-200):    {failed_queries}")

    if query_lengths:
        print(f"\nQuery length:")
        print(f"  Average:           {sum(query_lengths) / len(query_lengths):.0f} chars")
        print(f"  Min:               {min(query_lengths)} chars")
        print(f"  Max:               {max(query_lengths)} chars")

    if response_sizes:
        print(f"\nResponse size:")
        print(f"  Average rows:      {sum(response_sizes) / len(response_sizes):.1f}")
        print(f"  Min rows:          {min(response_sizes)}")
        print(f"  Max rows:          {max(response_sizes)}")

    print("\n" + "=" * 60)
    print("Commands used (top 20):")
    print("=" * 60)
    for command, count in command_counts.most_common(20):
        print(f"  {command:20s} {count:5d} queries")

    print("\n" + "=" * 60)
    print(f"Log file: {input_file}")
    print("=" * 60)


def main():
    if len(sys.argv) != 2:
        print("Usage: python3 analyze-queries.py queries.jsonl")
        sys.exit(1)

    input_file = Path(sys.argv[1])

    if not input_file.exists():
        print(f"Error: Input file {input_file} does not exist")
        sys.exit(1)

    analyze_log_file(input_file)


if __name__ == "__main__":
    main()
