"""
Property test executor.

Runs properties across multiple contexts and iterations, collecting failures.
"""

from dataclasses import dataclass, field
from typing import List, Optional
import random
from opensearchpy import OpenSearch

from ppl_correctness.datagen.context import IndexContext
from ppl_correctness.properties.base import Property, PropertyViolation


@dataclass
class TestResults:
    """Aggregated test run results"""
    total: int = 0
    passed: int = 0
    failures: List[PropertyViolation] = field(default_factory=list)

    @property
    def failed(self) -> int:
        return self.total - self.passed


class PropertyExecutor:
    """Executes properties across test contexts"""

    def __init__(
        self,
        host: str,
        contexts: List[IndexContext],
        properties: List[Property]
    ):
        self.client = OpenSearch([host])
        self.contexts = contexts
        self.properties = properties

    def run(self, iterations: int = 100, seed: Optional[int] = None) -> TestResults:
        """
        Run all properties for specified iterations.

        Each iteration:
        - Selects random context
        - Executes each property
        - Records violations
        """
        rng = random.Random(seed)
        results = TestResults()

        for i in range(iterations):
            context = rng.choice(self.contexts)

            for prop in self.properties:
                results.total += 1
                violations = prop.check(context, self.client)

                if not violations:
                    results.passed += 1
                else:
                    results.failures.extend(violations)

        return results
