"""
Hypothesis strategies for PPL query generation.

Provides composable strategies with automatic shrinking.
"""

from hypothesis import strategies as st
from ppl_correctness.datagen.context import IndexContext, Field, FieldType


def predicates(context: IndexContext, max_depth: int = 3):
    """Generate predicates with automatic shrinking"""

    comparable = context.get_comparable_fields()
    if not comparable:
        return st.just("true")

    # Base case: simple comparisons
    simple = st.one_of([
        _comparison_predicate(context),
        _equality_predicate(context),
        _null_check(context),
    ])

    if max_depth <= 1:
        return simple

    # Recursive: compound predicates
    return st.recursive(
        simple,
        lambda children: st.one_of([
            st.builds(lambda l, r: f"({l} AND {r})", children, children),
            st.builds(lambda l, r: f"({l} OR {r})", children, children),
            st.builds(lambda p: f"NOT ({p})", children),
        ]),
        max_leaves=max_depth
    )


def _comparison_predicate(context: IndexContext):
    """Generate field comparison: field > value"""
    numeric = context.get_numeric_fields()
    if not numeric:
        return st.just("true")

    return st.builds(
        lambda f, op, val: f"{f.name} {op} {val}",
        st.sampled_from(numeric),
        st.sampled_from(['=', '!=', '<', '<=', '>', '>=']),
        st.integers(-100, 100)
    )


def _equality_predicate(context: IndexContext):
    """Generate field equality: field = 'value'"""
    keyword = [f for f in context.fields if f.type == FieldType.KEYWORD]
    if not keyword:
        return st.just("true")

    return st.builds(
        lambda f, val: f"{f.name} = '{val}'",
        st.sampled_from(keyword),
        st.sampled_from(['red', 'green', 'blue', 'yellow', 'purple'])
    )


def _null_check(context: IndexContext):
    """Generate null checks: isnull(field)"""
    nullable = [f for f in context.fields if f.nullable]
    if not nullable:
        return st.just("true")

    return st.builds(
        lambda f: f"isnull({f.name})",
        st.sampled_from(nullable)
    )


def ppl_command(context: IndexContext):
    """Generate a single PPL command"""
    return st.one_of([
        where_command(context),
        stats_command(context),
        sort_command(context),
        eval_command(context),
        fields_command(context),
    ])


def where_command(context: IndexContext):
    """Generate: where <predicate>"""
    return st.builds(
        lambda p: f"where {p}",
        predicates(context)
    )


def stats_command(context: IndexContext):
    """Generate: stats agg(field) [by groupfield]"""
    numeric = context.get_numeric_fields()
    groupable = context.get_groupable_fields()

    if not numeric:
        return st.just("stats count()")

    agg_func = st.sampled_from(['count', 'sum', 'avg', 'min', 'max', 'stddev'])
    field = st.sampled_from(numeric)

    # With or without grouping
    base = st.builds(
        lambda agg, f: f"stats {agg}({f.name})" if agg != 'count' else "stats count()",
        agg_func,
        field
    )

    if not groupable:
        return base

    return st.one_of([
        base,
        st.builds(
            lambda b, g: f"{b} by {g.name}",
            base,
            st.sampled_from(groupable)
        )
    ])


def sort_command(context: IndexContext):
    """Generate: sort [+/-]field"""
    sortable = context.get_comparable_fields()
    if not sortable:
        return st.just("sort field_0")  # Fallback

    return st.builds(
        lambda f, dir: f"sort {dir}{f.name}",
        st.sampled_from(sortable),
        st.sampled_from(['+', '-', ''])
    )


def eval_command(context: IndexContext):
    """Generate: eval newfield = expression"""
    numeric = context.get_numeric_fields()
    if len(numeric) < 2:
        return st.just("eval tmp = 1")

    return st.builds(
        lambda f1, f2, op: f"eval computed = {f1.name} {op} {f2.name}",
        st.sampled_from(numeric),
        st.sampled_from(numeric),
        st.sampled_from(['+', '-', '*', '/'])
    )


def fields_command(context: IndexContext):
    """Generate: fields f1, f2, ..."""
    return st.builds(
        lambda fields: f"fields {', '.join(f.name for f in fields)}",
        st.lists(
            st.sampled_from(context.fields),
            min_size=1,
            max_size=min(5, len(context.fields)),
            unique_by=lambda f: f.name
        )
    )


def ppl_pipeline(context: IndexContext, min_commands: int = 1, max_commands: int = 5):
    """
    Generate complete PPL query pipeline.

    Returns: "source=index | cmd1 | cmd2 | ..."

    Ensures valid command ordering:
    - WHERE can appear anywhere
    - STATS/aggregations typically near end
    - EVAL before WHERE that uses computed fields
    """
    return st.builds(
        lambda cmds: f"source={context.name} | " + " | ".join(cmds),
        st.lists(
            ppl_command(context),
            min_size=min_commands,
            max_size=max_commands
        )
    )


# Strategy for generating test scenarios
def test_scenario(context: IndexContext):
    """
    Generate a complete test scenario with multiple related queries.

    Useful for differential testing where we need equivalent query forms.
    """
    return st.builds(
        lambda pred: {
            'base': f"source={context.name}",
            'predicate': pred,
            'query_with_pred': f"source={context.name} | where {pred}",
            'query_negated': f"source={context.name} | where NOT ({pred})",
            'query_null': f"source={context.name} | where isnull({pred})",
        },
        predicates(context)
    )
