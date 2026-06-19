#!/usr/bin/env python3
"""
Test script for the AdditivePipeProperty.

This property tests that PPL command chains are truly additive:
    source=index | A | B  ===  materialize(source=index | A) then source=index_A | B

Usage:
    # Start OpenSearch first:
    docker run -p 9200:9200 -e 'discovery.type=single-node' opensearchproject/opensearch:latest

    # Run the test:
    python test_additive_pipe.py
"""

import sys
from ppl_correctness.datagen.context import generate_contexts
from ppl_correctness.properties.additive_pipe import AdditivePipeProperty
from ppl_correctness.runner.executor import PropertyExecutor


def main():
    host = 'localhost:9200'

    print("=" * 60)
    print("Additive Pipe Property Test")
    print("=" * 60)
    print()

    print(f"Connecting to OpenSearch at {host}...")
    print("Generating test contexts...")

    try:
        # Generate a small number of test indices
        contexts = generate_contexts(
            host=host,
            num_contexts=2,  # Start with just 2 indices
            seed=42  # Fixed seed for reproducibility
        )

        print(f"Created {len(contexts)} test indices")
        for ctx in contexts:
            print(f"  - {ctx.name}: {len(ctx.fields)} fields, {ctx.doc_count} documents")

        print()
        print("Running AdditivePipeProperty tests...")
        print()

        # Create the property
        property_test = AdditivePipeProperty()

        # Run tests using the executor
        executor = PropertyExecutor(
            host=host,
            contexts=contexts,
            properties=[property_test]
        )

        # Run a small number of iterations to start
        results = executor.run(iterations=5, seed=42)

        print()
        print("=" * 60)
        print(f"Results: {results.passed}/{results.total} tests passed")
        print("=" * 60)

        if results.failures:
            print(f"\n❌ Found {len(results.failures)} failures:\n")
            for i, failure in enumerate(results.failures, 1):
                print(f"{i}. {failure}")
                print()
            return 1
        else:
            print("\n✅ All tests passed!")
            print()
            print("The additive pipe property holds for the tested commands.")
            print("This means that materializing intermediate results doesn't")
            print("change the final query output.")
            return 0

    except Exception as e:
        print(f"\n❌ Error: {e}")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == '__main__':
    sys.exit(main())
