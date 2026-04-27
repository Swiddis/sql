# Migration Guide: Manual RNG → Hypothesis

This guide shows how to migrate from manual random generation to Hypothesis strategies.

## Why Migrate?

**Before (Manual RNG):**
```python
def generate_predicate():
    rng = random.Random()
    field = rng.choice(fields)
    value = rng.randint(0, 100)
    return f"{field} > {value}"
```

**Problem:** Fails with `field_7 > 83`
- Hard to reproduce (need exact seed)
- No shrinking (is 83 important? is field_7 special?)
- Manual edge case coverage

**After (Hypothesis):**
```python
@given(field=st.sampled_from(fields), value=st.integers(0, 100))
def test_predicate(field, value):
    pred = f"{field} > {value}"
    ...
```

**Benefits:**
- Automatically shrinks to `field_0 > 0` (minimal case)
- Deterministic replay with `@example(field_0, 0)`
- Better statistical distribution

## Step-by-Step Migration

### Step 1: Install Hypothesis

```bash
pip install hypothesis
```

### Step 2: Convert Generator to Strategy

**Before:**
```python
class PPLQueryGenerator:
    def __init__(self, context: IndexContext, rng: random.Random):
        self.context = context
        self.rng = rng
    
    def generate_predicate(self):
        field = self.rng.choice(self.context.fields)
        op = self.rng.choice(['>', '<', '='])
        value = self.rng.randint(0, 100)
        return f"{field.name} {op} {value}"
```

**After:**
```python
from hypothesis import strategies as st

def predicate_strategy(context: IndexContext):
    return st.builds(
        lambda f, op, val: f"{f.name} {op} {val}",
        st.sampled_from(context.fields),
        st.sampled_from(['>', '<', '=']),
        st.integers(0, 100)
    )
```

### Step 3: Update Property to Use Strategy

**Before:**
```python
class MyProperty(Property):
    def check(self, context, client):
        rng = random.Random()
        generator = PPLQueryGenerator(context, rng)
        
        for _ in range(10):  # Manual iteration
            pred = generator.generate_predicate()
            # Test predicate...
```

**After:**
```python
from hypothesis import given, settings

class MyProperty(Property):
    def check(self, context, client):
        self._context = context
        self._client = client
        self._violations = []
        
        self._run_hypothesis_test()
        return self._violations
    
    @settings(max_examples=10)
    @given(pred=st.data())
    def _run_hypothesis_test(self, pred):
        from strategies import predicate_strategy
        
        predicate = pred.draw(predicate_strategy(self._context))
        # Test predicate...
        # On failure, raise AssertionError for shrinking
```

### Step 4: Handle Shrinking

**Key:** Raise `AssertionError` on failures to trigger shrinking.

```python
@given(pred=predicate_strategy(context))
def test_tlp(pred):
    results = execute_tlp_queries(pred)
    
    if results['union_count'] != results['total_count']:
        # Hypothesis will shrink pred to minimal failing case
        raise AssertionError(
            f"TLP failed for '{pred}': "
            f"{results['union_count']} != {results['total_count']}"
        )
```

### Step 5: Add Regression Tests

When Hypothesis finds a bug, add it as a permanent test:

```python
from hypothesis import given, example

@given(predicate_strategy(context))
@example("field_0 > 0")  # Regression: found by shrinking
@example("field_1 = NULL")  # Regression: NULL handling bug
def test_tlp(pred):
    # Test will always run examples first, then generate new ones
    ...
```

## Pattern Comparison

### Generating Queries

**Before:**
```python
def generate_where_query(self):
    base = f"source={self.context.name}"
    pred = self.generate_predicate()
    return f"{base} | where {pred}"
```

**After:**
```python
def where_query_strategy(context):
    return st.builds(
        lambda pred: f"source={context.name} | where {pred}",
        predicate_strategy(context)
    )
```

### Generating Pipelines

**Before:**
```python
def generate_pipeline(self, length=3):
    commands = []
    for _ in range(length):
        cmd = self.rng.choice([
            self.generate_where_query,
            self.generate_stats_query,
            self.generate_sort_query
        ])
        commands.append(cmd())
    return " | ".join(commands)
```

**After:**
```python
def pipeline_strategy(context, max_length=3):
    command_strategy = st.one_of([
        where_command_strategy(context),
        stats_command_strategy(context),
        sort_command_strategy(context)
    ])
    
    return st.builds(
        lambda cmds: " | ".join(cmds),
        st.lists(command_strategy, min_size=1, max_size=max_length)
    )
```

### Testing Multiple Iterations

**Before:**
```python
def run_tests(iterations=100):
    for i in range(iterations):
        context = random.choice(contexts)
        pred = generate_predicate(context)
        test_property(context, pred)
```

**After:**
```python
@given(
    context=st.sampled_from(contexts),
    pred=st.data()
)
@settings(max_examples=100)
def test_property(context, pred):
    predicate = pred.draw(predicate_strategy(context))
    # Test...
```

## Advanced Patterns

### Recursive Strategies

For nested predicates like `(a > 5) AND (b < 10)`:

```python
def predicate_strategy(context, max_depth=3):
    simple = simple_predicate_strategy(context)
    
    return st.recursive(
        simple,
        lambda children: st.one_of([
            st.builds(lambda l, r: f"({l}) AND ({r})", children, children),
            st.builds(lambda l, r: f"({l}) OR ({r})", children, children),
            st.builds(lambda p: f"NOT ({p})", children),
        ]),
        max_leaves=max_depth
    )
```

### Constrained Generation

Ensure generated values satisfy constraints:

```python
from hypothesis import assume

@given(
    field1=st.sampled_from(numeric_fields),
    field2=st.sampled_from(numeric_fields)
)
def test_arithmetic(field1, field2):
    # Ensure fields are different
    assume(field1.name != field2.name)
    
    # Test field1 + field2 ...
```

### Custom Shrinking

Define custom shrinking for domain-specific types:

```python
from hypothesis.strategies import composite

@composite
def command_pipeline(draw, context, max_commands=5):
    # Generate commands with custom shrinking order
    commands = []
    
    # Always start with WHERE (simplest)
    if draw(st.booleans()):
        commands.append(draw(where_command(context)))
    
    # Add optional additional commands
    for _ in range(draw(st.integers(0, max_commands - 1))):
        commands.append(draw(ppl_command(context)))
    
    return " | ".join(commands)

# Shrinking will try:
# 1. Remove optional commands
# 2. Simplify remaining commands
# 3. Minimize predicate complexity
```

## Common Pitfalls

### 1. Not Raising AssertionError

**Wrong:**
```python
@given(pred=predicate_strategy(context))
def test_property(pred):
    if not check_invariant(pred):
        print("Failed!")  # Hypothesis won't shrink
        return False
```

**Right:**
```python
@given(pred=predicate_strategy(context))
def test_property(pred):
    assert check_invariant(pred), f"Invariant violated for: {pred}"
    # Hypothesis will shrink on AssertionError
```

### 2. Over-constraining with assume()

**Wrong:**
```python
@given(st.integers())
def test_even(n):
    assume(n % 2 == 0)  # Rejects 50% of inputs - slow!
    ...
```

**Right:**
```python
@given(st.integers().map(lambda n: n * 2))  # Generate evens directly
def test_even(n):
    ...
```

### 3. Non-deterministic Tests

**Wrong:**
```python
@given(pred=predicate_strategy(context))
def test_property(pred):
    result = execute_query(pred)
    time.sleep(0.1)  # Wait for eventual consistency - NON-DETERMINISTIC
    check_result(result)
```

**Right:**
```python
@given(pred=predicate_strategy(context))
def test_property(pred):
    result = execute_query(pred)
    client.indices.refresh()  # Explicit refresh - deterministic
    check_result(result)
```

## Testing the Migration

Run both implementations side-by-side:

```python
def test_migration_equivalence():
    """Verify Hypothesis generates same distribution as manual RNG"""
    
    # Manual RNG
    manual_preds = []
    rng = random.Random(42)
    gen = PPLQueryGenerator(context, rng)
    for _ in range(100):
        manual_preds.append(gen.generate_predicate())
    
    # Hypothesis
    hypothesis_preds = []
    
    @given(pred=predicate_strategy(context))
    @settings(max_examples=100, database=None)
    def collect_preds(pred):
        hypothesis_preds.append(pred)
    
    collect_preds()
    
    # Compare distributions
    assert len(manual_preds) == len(hypothesis_preds)
    # Both should cover similar operators, fields, etc.
```

## Performance Notes

- Hypothesis is **slightly slower** than manual RNG (overhead of ~10-20%)
- Shrinking adds time on failures (but saves debugging time!)
- Use `@settings(max_examples=N)` to control runtime
- Disable phases if needed: `phases=[Phase.generate]` (skip shrinking)

## Next Steps

1. Start with TLP property (simplest)
2. Verify shrinking works correctly
3. Migrate other properties one-by-one
4. Add regression `@example` decorators as bugs found
5. Document shrinking behavior for team

## Resources

- [Hypothesis Docs](https://hypothesis.readthedocs.io/)
- [Hypothesis Strategies](https://hypothesis.readthedocs.io/en/latest/data.html)
- [Property-Based Testing Guide](https://hypothesis.works/articles/what-is-property-based-testing/)
