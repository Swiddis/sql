"""
Complex type support for PPL testing.

Handles:
- Nested objects (user.name, address.city)
- Arrays/multi-value fields
- Timestamp types with date functions
- GeoPoint types
"""

from dataclasses import dataclass
from typing import Any, Dict, List, Optional
from enum import Enum
import random
from datetime import datetime, timedelta


class ComplexFieldType(Enum):
    """Extended field types"""
    # Nested object
    OBJECT = "object"
    # Array types
    ARRAY_INT = "array<int>"
    ARRAY_STRING = "array<string>"
    # Temporal
    TIMESTAMP = "timestamp"
    # Geo
    GEO_POINT = "geo_point"


@dataclass
class NestedField:
    """Represents a nested object field"""
    parent: str
    name: str
    type: str

    @property
    def path(self) -> str:
        return f"{self.parent}.{self.name}"


@dataclass
class ComplexField:
    """Field with complex type"""
    name: str
    type: ComplexFieldType
    nested_fields: Optional[List[NestedField]] = None
    element_type: Optional[str] = None  # For arrays


class ComplexValueGenerator:
    """Generate values for complex types"""

    def __init__(self, rng: random.Random):
        self.rng = rng

    def generate_object(self, field: ComplexField) -> Dict[str, Any]:
        """Generate nested object value"""
        if not field.nested_fields:
            return {}

        obj = {}
        for nested in field.nested_fields:
            if nested.type == "string":
                obj[nested.name] = self.rng.choice(['alpha', 'beta', 'gamma'])
            elif nested.type == "integer":
                obj[nested.name] = self.rng.randint(0, 100)
            elif nested.type == "boolean":
                obj[nested.name] = self.rng.choice([True, False])

        return obj

    def generate_array(self, field: ComplexField) -> List[Any]:
        """Generate array value"""
        size = self.rng.randint(0, 5)

        if field.type == ComplexFieldType.ARRAY_INT:
            return [self.rng.randint(-100, 100) for _ in range(size)]
        elif field.type == ComplexFieldType.ARRAY_STRING:
            words = ['apple', 'banana', 'cherry', 'date']
            return [self.rng.choice(words) for _ in range(size)]

        return []

    def generate_timestamp(self) -> str:
        """Generate ISO timestamp"""
        base = datetime(2024, 1, 1)
        delta = timedelta(
            days=self.rng.randint(0, 365),
            hours=self.rng.randint(0, 23),
            minutes=self.rng.randint(0, 59)
        )
        return (base + delta).isoformat()

    def generate_geo_point(self) -> Dict[str, float]:
        """Generate geo point"""
        return {
            "lat": self.rng.uniform(-90, 90),
            "lon": self.rng.uniform(-180, 180)
        }


# Hypothesis strategies for complex types
try:
    from hypothesis import strategies as st

    def nested_object_strategy(field: ComplexField):
        """Strategy for nested objects"""
        if not field.nested_fields:
            return st.just({})

        strategies = {}
        for nested in field.nested_fields:
            if nested.type == "string":
                strategies[nested.name] = st.sampled_from(['alpha', 'beta', 'gamma'])
            elif nested.type == "integer":
                strategies[nested.name] = st.integers(0, 100)
            elif nested.type == "boolean":
                strategies[nested.name] = st.booleans()

        return st.fixed_dictionaries(strategies)

    def array_strategy(field: ComplexField):
        """Strategy for arrays"""
        if field.type == ComplexFieldType.ARRAY_INT:
            return st.lists(st.integers(-100, 100), max_size=5)
        elif field.type == ComplexFieldType.ARRAY_STRING:
            return st.lists(
                st.sampled_from(['apple', 'banana', 'cherry', 'date']),
                max_size=5
            )
        return st.lists(st.nothing(), max_size=0)

    def timestamp_strategy():
        """Strategy for timestamps"""
        return st.builds(
            lambda days, hours: (datetime(2024, 1, 1) + timedelta(days=days, hours=hours)).isoformat(),
            st.integers(0, 365),
            st.integers(0, 23)
        )

    def geo_point_strategy():
        """Strategy for geo points"""
        return st.builds(
            lambda lat, lon: {"lat": lat, "lon": lon},
            st.floats(-90, 90),
            st.floats(-180, 180)
        )

except ImportError:
    # Hypothesis not available
    pass


# PPL function generators for complex types

def timestamp_function_predicates(field_name: str) -> List[str]:
    """
    Generate PPL predicates using timestamp functions.

    Examples:
    - YEAR(timestamp_field) = 2024
    - MONTH(timestamp_field) > 6
    - DAY_OF_WEEK(timestamp_field) = 1
    """
    return [
        f"YEAR({field_name}) = 2024",
        f"MONTH({field_name}) BETWEEN 1 AND 6",
        f"DAY({field_name}) > 15",
        f"HOUR({field_name}) < 12",
        f"DAYOFWEEK({field_name}) IN (1, 7)",  # Weekend
        f"DATE_FORMAT({field_name}, 'yyyy-MM') = '2024-06'",
    ]


def nested_field_predicates(field: ComplexField) -> List[str]:
    """
    Generate predicates for nested fields.

    Examples:
    - user.age > 21
    - address.city = 'Seattle'
    """
    if not field.nested_fields:
        return []

    predicates = []
    for nested in field.nested_fields:
        path = f"{field.name}.{nested.name}"
        if nested.type == "integer":
            predicates.append(f"{path} > 50")
            predicates.append(f"{path} BETWEEN 10 AND 90")
        elif nested.type == "string":
            predicates.append(f"{path} = 'alpha'")
            predicates.append(f"{path} IN ('alpha', 'beta')")

    return predicates


def array_function_predicates(field_name: str, element_type: str) -> List[str]:
    """
    Generate predicates using array functions.

    PPL array functions (if supported):
    - json_array_length(field) > 0
    - field IN [1, 2, 3]
    """
    predicates = []

    # Check array length
    predicates.append(f"json_array_length({field_name}) > 0")
    predicates.append(f"json_array_length({field_name}) = 3")

    # Array containment (if supported)
    if element_type == "integer":
        predicates.append(f"array_contains({field_name}, 42)")
    elif element_type == "string":
        predicates.append(f"array_contains({field_name}, 'apple')")

    return predicates


def geo_function_predicates(field_name: str) -> List[str]:
    """
    Generate geo predicates (if PPL supports geo functions).

    Examples:
    - geo_distance(location, geo_point(47.6, -122.3)) < 10km
    """
    return [
        f"geo_distance({field_name}, geo_point(47.6, -122.3)) < 10",
        f"{field_name}.lat > 0",
        f"{field_name}.lon BETWEEN -180 AND -120",
    ]


# Property testing helpers for complex types

def nested_field_tlp_property(context, field: ComplexField):
    """
    TLP test specialized for nested fields.

    Tests that nested field predicates correctly partition results.
    """
    if not field.nested_fields:
        return None

    nested = field.nested_fields[0]
    path = f"{field.name}.{nested.name}"

    if nested.type == "integer":
        predicate = f"{path} > 50"
    else:
        predicate = f"{path} = 'alpha'"

    return {
        'base': f"source={context.name}",
        'predicate': predicate,
        'query_true': f"source={context.name} | where {predicate}",
        'query_false': f"source={context.name} | where NOT ({predicate})",
        'query_null': f"source={context.name} | where isnull({predicate})",
    }


def timestamp_aggregation_property(context, field_name: str):
    """
    Test that timestamp aggregations preserve conservation laws.

    Example:
    - SUM(stats count() by YEAR(ts)) = stats count()
    """
    return {
        'total': f"source={context.name} | stats count()",
        'grouped_by_year': f"source={context.name} | stats count() by YEAR({field_name})",
        'grouped_by_month': f"source={context.name} | stats count() by MONTH({field_name})",
    }


def array_length_invariant(context, field_name: str):
    """
    Test array length invariants.

    Invariant: COUNT(docs where array_length > 0) <= COUNT(all docs)
    """
    return {
        'total': f"source={context.name} | stats count()",
        'with_array': f"source={context.name} | where json_array_length({field_name}) > 0 | stats count()",
    }
