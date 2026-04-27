"""
Base property interface for correctness testing.
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any, List
from ppl_correctness.datagen.context import IndexContext


@dataclass
class PropertyViolation:
    """Records a property violation"""
    property_name: str
    query: str
    expected: Any
    actual: Any
    message: str

    def __str__(self):
        return (f"{self.property_name} violated: {self.message}\n"
                f"  Query: {self.query}\n"
                f"  Expected: {self.expected}\n"
                f"  Actual: {self.actual}")


class Property(ABC):
    """Base class for testable properties"""

    @property
    @abstractmethod
    def name(self) -> str:
        """Property identifier"""
        pass

    @abstractmethod
    def check(self, context: IndexContext, client: Any) -> List[PropertyViolation]:
        """
        Execute property test on given context.

        Returns list of violations (empty if property holds).
        """
        pass
