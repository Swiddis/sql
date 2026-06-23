#!/usr/bin/env python3
"""
Test extraction equivalence property (spath, rex, lookup).

Usage:
    python test_extraction.py --type spath --iterations 10
"""

import argparse
import random
from opensearchpy import OpenSearch

from ppl_correctness.datagen.context import (
    generate_correlated_pair_spath,
    generate_correlated_pair_rex,
    generate_correlated_pair_lookup
)
from ppl_correctness.properties.extraction import ExtractionEquivalenceProperty


def main():
    parser = argparse.ArgumentParser(description='Test extraction equivalence')
    parser.add_argument('--host', default='localhost:9200', help='OpenSearch host')
    parser.add_argument('--type', default='spath', choices=['spath', 'rex', 'lookup'],
                        help='Extraction type to test')
    parser.add_argument('--iterations', type=int, default=10, help='Number of test iterations')
    parser.add_argument('--seed', type=int, help='Random seed for reproducibility')
    args = parser.parse_args()

    client = OpenSearch([args.host])
    rng = random.Random(args.seed)
    prop = ExtractionEquivalenceProperty()

    passed = 0
    failed = 0
    violations = []

    print(f"Testing {args.type} extraction equivalence...")
    print(f"Iterations: {args.iterations}")
    print(f"Host: {args.host}")
    if args.seed:
        print(f"Seed: {args.seed}")
    print()

    for i in range(args.iterations):
        print(f"[{i+1}/{args.iterations}] ", end='', flush=True)

        if args.type == 'spath':
            corr_set = generate_correlated_pair_spath(
                client, rng, base_name=f"spath_test_{i}", doc_count=100
            )
        elif args.type == 'rex':
            corr_set = generate_correlated_pair_rex(
                client, rng, base_name=f"rex_test_{i}", doc_count=100
            )
        elif args.type == 'lookup':
            corr_set = generate_correlated_pair_lookup(
                client, rng, base_name=f"lookup_test_{i}", doc_count=100
            )
        else:
            print(f"ERROR: {args.type} not implemented yet")
            continue

        viols = prop.check(corr_set, client)

        if viols:
            print(f"FAIL ({len(viols)} violations)")
            failed += 1
            violations.extend(viols)
        else:
            print("PASS")
            passed += 1

        # Cleanup
        client.indices.delete(index=corr_set.base.name, ignore=[404])
        for variant in corr_set.variants.values():
            client.indices.delete(index=variant.name, ignore=[404])

    print()
    print("=" * 60)
    print(f"Results: {passed} passed, {failed} failed")
    print(f"Pass rate: {100 * passed / args.iterations:.1f}%")

    if violations:
        print()
        print("Violations:")
        for v in violations:
            print(f"\n  {v}")

    return 0 if failed == 0 else 1


if __name__ == '__main__':
    exit(main())
