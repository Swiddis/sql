#!/usr/bin/env python3
"""
Test runner specifically for bug-finding properties.

This runs the properties that are designed to catch known GitHub issues.
Run this against OpenSearch to see if bugs are present or fixed.
"""

import argparse
import logging
from opensearchpy import OpenSearch

from ppl_correctness.properties.array_properties import (
    ArrayGroupByAtomicity,
    ArrayWhereFilterConsistency,
    ArraySortDeterminism
)
from ppl_correctness.properties.nested_object_properties import (
    NestedFieldAccessNullCheck,
    NestedFieldFilterConsistency
)
from ppl_correctness.properties.text_keyword_properties import (
    IsNotNullFilterAggregation,
    FilterAggregationFieldConsistency
)
from ppl_correctness.properties.rename_properties import (
    RenameFieldPreservation,
    RenameEvalInteraction,
    CommandOrderIndependence
)


logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s'
)
logger = logging.getLogger(__name__)


def main():
    parser = argparse.ArgumentParser(
        description='Test for known PPL bugs'
    )
    parser.add_argument(
        '--host',
        default='localhost:9200',
        help='OpenSearch host'
    )
    parser.add_argument(
        '--category',
        choices=['arrays', 'nested', 'text-keyword', 'rename', 'all'],
        default='all',
        help='Category of bugs to test'
    )

    args = parser.parse_args()

    client = OpenSearch([args.host])

    # Define property sets
    all_properties = {
        'arrays': [
            ('Issue #5333', [
                ArrayGroupByAtomicity(),
                ArrayWhereFilterConsistency(),
                ArraySortDeterminism(),
            ]),
        ],
        'nested': [
            ('Issue #4906', [
                NestedFieldAccessNullCheck(),
                NestedFieldFilterConsistency(),
            ]),
        ],
        'text-keyword': [
            ('Issue #4463', [
                IsNotNullFilterAggregation(),
                FilterAggregationFieldConsistency(),
            ]),
        ],
        'rename': [
            ('Issue #5150', [
                RenameFieldPreservation(),
                RenameEvalInteraction(),
                CommandOrderIndependence(),
            ]),
        ],
    }

    # Select properties to run
    if args.category == 'all':
        properties_to_run = all_properties
    else:
        properties_to_run = {args.category: all_properties[args.category]}

    # Run tests
    total_properties = 0
    total_violations = 0

    for category, issue_groups in properties_to_run.items():
        logger.info(f"\n{'=' * 70}")
        logger.info(f"Testing {category.upper()} bugs")
        logger.info('=' * 70)

        for issue_name, properties in issue_groups:
            logger.info(f"\n{issue_name}:")

            for prop in properties:
                total_properties += 1
                logger.info(f"  Running {prop.name}...")

                try:
                    violations = prop.check(None, client)

                    if not violations:
                        logger.info(f"    ✓ PASSED (no violations)")
                    else:
                        total_violations += len(violations)
                        logger.warning(f"    ✗ FAILED with {len(violations)} violation(s):")
                        for v in violations:
                            logger.warning(f"      - {v.message}")
                            logger.warning(f"        Query: {v.query[:100]}...")
                            logger.warning(f"        Expected: {v.expected}")
                            logger.warning(f"        Actual: {v.actual}")

                except Exception as e:
                    logger.error(f"    ✗ ERROR: {e}")
                    total_violations += 1

    # Summary
    logger.info(f"\n{'=' * 70}")
    logger.info("SUMMARY")
    logger.info('=' * 70)
    logger.info(f"Properties run: {total_properties}")
    logger.info(f"Total violations: {total_violations}")

    if total_violations == 0:
        logger.info("✓ All tests passed! No known bugs detected.")
    else:
        logger.warning(f"✗ Found issues in {total_violations} tests.")
        logger.warning("These likely indicate presence of known bugs.")

    return 1 if total_violations > 0 else 0


if __name__ == '__main__':
    exit(main())
