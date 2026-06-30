"""
Known bug registry and skip logic for property tests.

Centralizes workarounds for upstream bugs. Each entry has:
- pattern: predicate that matches affected queries/fields/contexts
- reason: issue number or description
- check function: returns True if this bug affects the given input

Usage:
    from ppl_correctness.known_bugs import should_skip

    skip_reason = should_skip(query="stats count() by array_field")
    if skip_reason:
        assume(False)  # Skip this test case in Hypothesis
"""

from typing import Optional, Callable, Any
from dataclasses import dataclass


@dataclass
class KnownBug:
    """A known upstream bug that should skip test cases."""
    name: str
    reason: str
    check: Callable[[Any], bool]


# Registry of known bugs
KNOWN_BUGS = [
    KnownBug(
        name="array_group_by_explosion",
        reason="Issue #5333: GROUP BY on array fields explodes count",
        check=lambda ctx: (
            hasattr(ctx, 'query') and 'by' in ctx.query.lower() and
            hasattr(ctx, 'field') and getattr(ctx.field, 'is_array', False)
        )
    ),
    KnownBug(
        name="array_field_operations",
        reason="Multiple array-related issues - generation disabled",
        check=lambda ctx: (
            hasattr(ctx, 'field') and getattr(ctx.field, 'is_array', False)
        )
    ),
    KnownBug(
        name="rename_dedup_nullification",
        reason="Issue #5150: RENAME + DEDUP nullifies renamed fields",
        check=lambda ctx: (
            hasattr(ctx, 'query') and
            'rename' in ctx.query.lower() and
            'dedup' in ctx.query.lower()
        )
    ),
    KnownBug(
        name="text_keyword_filter_mismatch",
        reason="Issue #4463: isnotnull filter fails with TEXT+KEYWORD subfields",
        check=lambda ctx: (
            hasattr(ctx, 'query') and 'isnotnull' in ctx.query.lower() and
            hasattr(ctx, 'field') and getattr(ctx.field, 'subfields', None) and
            'keyword' in getattr(ctx.field, 'subfields', {})
        )
    ),
    KnownBug(
        name="boolean_aggregation_unsupported",
        reason="approx_distinct not implemented for BOOLEAN type",
        check=lambda ctx: (
            hasattr(ctx, 'query') and 'approx_distinct' in ctx.query.lower() and
            hasattr(ctx, 'field') and getattr(ctx.field, 'type', None) and
            str(getattr(ctx.field, 'type', '')).endswith('BOOLEAN')
        )
    ),
]


def should_skip(query: Optional[str] = None, field=None, **kwargs) -> Optional[str]:
    """
    Check if test case matches known bug patterns.

    Args:
        query: PPL query string
        field: Field object being tested
        **kwargs: Additional context (command, context, etc.)

    Returns:
        Skip reason if matches a known bug, None otherwise

    Example:
        from hypothesis import assume

        skip_reason = should_skip(query=ppl_query, field=field_obj)
        if skip_reason:
            assume(False)  # Skip this case
    """
    # Build context object from kwargs
    class Context:
        pass

    ctx = Context()
    ctx.query = query or ""
    ctx.field = field
    for k, v in kwargs.items():
        setattr(ctx, k, v)

    # Check each known bug
    for bug in KNOWN_BUGS:
        try:
            if bug.check(ctx):
                return f"{bug.name}: {bug.reason}"
        except (AttributeError, TypeError):
            # Bug check doesn't apply to this context
            continue

    return None


def register_bug(name: str, reason: str, check: Callable[[Any], bool]):
    """Register a new known bug pattern at runtime."""
    KNOWN_BUGS.append(KnownBug(name, reason, check))
