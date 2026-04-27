"""
Context-aware PPL query generation.

Generates syntactically and semantically valid PPL queries based on index schema.
"""

from typing import List, Optional
import random
from ppl_correctness.datagen.context import IndexContext, Field, FieldType


class PPLQueryGenerator:
    """Generates random PPL queries constrained by index schema"""

    def __init__(self, context: IndexContext, rng: random.Random):
        self.context = context
        self.rng = rng

    def generate_predicate(self, depth: int = 0) -> str:
        """Generate a boolean predicate for WHERE clauses"""
        if depth > 2:  # Limit recursion
            return self._generate_simple_predicate()

        choice = self.rng.random()
        if choice < 0.7 or depth == 0:  # Mostly simple predicates
            return self._generate_simple_predicate()
        elif choice < 0.85:  # AND
            left = self.generate_predicate(depth + 1)
            right = self.generate_predicate(depth + 1)
            return f"({left} AND {right})"
        else:  # OR
            left = self.generate_predicate(depth + 1)
            right = self.generate_predicate(depth + 1)
            return f"({left} OR {right})"

    def _generate_simple_predicate(self) -> str:
        """Generate a simple comparison predicate"""
        field = self.rng.choice(self.context.get_comparable_fields())

        if field.type == FieldType.BOOLEAN:
            return field.name

        if field.type in (FieldType.INTEGER, FieldType.LONG, FieldType.FLOAT, FieldType.DOUBLE):
            op = self.rng.choice(['=', '!=', '<', '<=', '>', '>='])
            value = self.rng.randint(-100, 100) if field.type in (FieldType.INTEGER, FieldType.LONG) else self.rng.uniform(-100, 100)
            return f"{field.name} {op} {value}"

        if field.type == FieldType.KEYWORD:
            op = self.rng.choice(['=', '!='])
            value = self.rng.choice(['red', 'green', 'blue', 'yellow', 'purple'])
            return f"{field.name} {op} '{value}'"

        # Default: equality check
        return f"{field.name} = {field.name}"

    def generate_base_query(self) -> str:
        """Generate a basic source query"""
        return f"source={self.context.name}"

    def generate_where_query(self) -> str:
        """Generate query with WHERE filter"""
        predicate = self.generate_predicate()
        return f"{self.generate_base_query()} | where {predicate}"

    def generate_stats_query(self) -> str:
        """Generate aggregation query"""
        base = self.generate_base_query()

        # Choose aggregation function
        numeric_fields = self.context.get_numeric_fields()
        if not numeric_fields:
            return f"{base} | stats count()"

        field = self.rng.choice(numeric_fields)
        agg = self.rng.choice(['count', 'sum', 'avg', 'min', 'max'])

        if agg == 'count':
            query = f"{base} | stats count()"
        else:
            query = f"{base} | stats {agg}({field.name})"

        # Optionally add grouping
        if self.rng.random() < 0.5:
            groupable = self.context.get_groupable_fields()
            if groupable:
                group_field = self.rng.choice(groupable)
                query += f" by {group_field.name}"

        return query

    def generate_sort_query(self) -> str:
        """Generate query with sorting"""
        base = self.generate_base_query()
        sortable = self.context.get_comparable_fields()

        if not sortable:
            return base

        field = self.rng.choice(sortable)
        direction = self.rng.choice(['+', '-'])

        return f"{base} | sort {direction}{field.name}"

    def generate_fields_query(self) -> str:
        """Generate query with field projection"""
        base = self.generate_base_query()

        num_fields = self.rng.randint(1, min(3, len(self.context.fields)))
        selected = self.rng.sample(self.context.fields, num_fields)

        field_list = ', '.join(f.name for f in selected)
        return f"{base} | fields {field_list}"

    def generate_eval_query(self) -> str:
        """Generate query with EVAL (computed field)"""
        base = self.generate_base_query()
        numeric = self.context.get_numeric_fields()

        if len(numeric) < 2:
            return base

        f1, f2 = self.rng.sample(numeric, 2)
        op = self.rng.choice(['+', '-', '*', '/'])

        return f"{base} | eval computed = {f1.name} {op} {f2.name}"
