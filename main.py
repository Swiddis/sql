"""
PPL Correctness Testing Framework - Main Entry Point
"""

import argparse
import logging
from typing import List

from ppl_correctness.datagen.context import generate_contexts
from ppl_correctness.properties.tlp import TernaryLogicPartitioning
from ppl_correctness.properties.sorting import SortingInvariant, SortHeadEquivalence
from ppl_correctness.properties.aggregation import AggregationConservation, MonotonicityInvariant
from ppl_correctness.properties.advanced_aggregation import (
    AvgConservation,
    StddevInvariant,
    DistinctCountInvariant,
    StringDateMinMax,
    HighCardinalityGroupBy
)
from ppl_correctness.properties.atomic import (
    GroupByConservation,
    FieldAccessConsistency,
    FilterAggregationConsistency,
    FieldValuePreservation
)
from ppl_correctness.properties.additive_pipe import AdditivePipeProperty
from ppl_correctness.properties.pushdown import PushdownProperty, TimeRangePushdownProperty
from ppl_correctness.runner.executor import PropertyExecutor


logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s'
)
logger = logging.getLogger(__name__)


def main():
    parser = argparse.ArgumentParser(description='PPL Correctness Testing')
    parser.add_argument('--host', default='localhost:9200', help='OpenSearch host')
    parser.add_argument('--username', help='HTTP basic auth username')
    parser.add_argument('--password', help='HTTP basic auth password')
    parser.add_argument('--indices', type=int, default=5, help='Number of test indices')
    parser.add_argument('--property', default='all', choices=['tlp', 'sort', 'aggregation', 'atomic', 'additive-pipe', 'pushdown', 'all'])
    parser.add_argument('--iterations', type=int, default=100, help='Test iterations')
    parser.add_argument('--seed', type=int, help='Random seed for reproducibility')

    args = parser.parse_args()

    logger.info(f"Generating {args.indices} test contexts...")
    contexts = generate_contexts(
        host=args.host,
        username=args.username,
        password=args.password,
        num_contexts=args.indices,
        seed=args.seed
    )

    logger.info(f"Running {args.property} property tests ({args.iterations} iterations)...")

    properties = []
    if args.property == 'tlp' or args.property == 'all':
        properties.append(TernaryLogicPartitioning())
    if args.property == 'sort' or args.property == 'all':
        properties.append(SortingInvariant())
        properties.append(SortHeadEquivalence())
    if args.property == 'aggregation' or args.property == 'all':
        properties.append(AggregationConservation())
        properties.append(MonotonicityInvariant())
        properties.append(AvgConservation())
        properties.append(StddevInvariant())
        properties.append(DistinctCountInvariant())
        properties.append(StringDateMinMax())
        properties.append(HighCardinalityGroupBy())
    if args.property == 'atomic' or args.property == 'all':
        # Atomic properties that catch specific bugs
        properties.append(GroupByConservation())
        properties.append(FieldAccessConsistency())
        properties.append(FilterAggregationConsistency())
        properties.append(FieldValuePreservation())
    if args.property == 'additive-pipe' or args.property == 'all':
        properties.append(AdditivePipeProperty())
    if args.property == 'pushdown' or args.property == 'all':
        properties.append(PushdownProperty())
        properties.append(TimeRangePushdownProperty())

    executor = PropertyExecutor(
        host=args.host,
        username=args.username,
        password=args.password,
        contexts=contexts,
        properties=properties
    )

    results = executor.run(iterations=args.iterations, seed=args.seed)

    logger.info("=" * 60)
    logger.info(f"Results: {results.passed}/{results.total} passed")
    if results.failures:
        logger.error(f"Found {len(results.failures)} failures:")
        for failure in results.failures[:10]:  # Show first 10
            logger.error(f"  {failure}")

    return 0 if results.failures == [] else 1


if __name__ == '__main__':
    exit(main())
