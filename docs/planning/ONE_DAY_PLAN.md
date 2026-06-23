# One-Day Proof: Correlated Schema Properties

**Goal:** Prove multi-index correlated schema approach works for spath/rex/lookup.

**Core insight:** Extract/enrich commands (spath, rex, lookup) are really type/representation transformations. Property: `index_A | transform | tail ≡ index_B | tail` where B is pre-transformed version of A.

---

## Architecture Changes (2-3 hours)

### 1. Multi-Index Context (~1 hour)

```python
# ppl_correctness/datagen/context.py

@dataclass
class CorrelatedIndexSet:
    """Multiple indices with known relationships"""
    base: IndexContext           # "raw" data
    variants: Dict[str, IndexContext]  # name → transformed version
    relationships: List[Relationship]
    
@dataclass  
class Relationship:
    """Describes how two indices relate"""
    type: str  # 'spath', 'rex', 'lookup', 'cast'
    source_index: str
    target_index: str
    mapping: Dict[str, str]  # source_field → target_field
    extraction_pattern: Optional[str]  # for spath/rex

# Example:
# base has field `json_data: text` = '{"user": "alice", "count": 42}'
# spath_variant has fields `user: keyword`, `count: integer`
# relationship = Relationship(
#     type='spath',
#     source_index='base',
#     target_index='spath_variant', 
#     mapping={'json_data': ['user', 'count']},
#     extraction_pattern='json'
# )
```

### 2. Correlated Data Gen (~30 min)

```python
# Generate same logical data in different representations

def generate_correlated_pair_spath(rng) -> CorrelatedIndexSet:
    """One index has JSON-as-text, other has extracted fields."""
    # Base index
    base_schema = [
        Field('id', FieldType.INTEGER),
        Field('json_payload', FieldType.TEXT),  # '{"status":"ok","value":42}'
    ]
    base_docs = []
    extracted_docs = []
    
    for i in range(100):
        status = rng.choice(['ok', 'error', 'pending'])
        value = rng.randint(0, 100)
        json_str = json.dumps({'status': status, 'value': value})
        
        base_docs.append({'id': i, 'json_payload': json_str})
        extracted_docs.append({'id': i, 'status': status, 'value': value})
    
    base = IndexContext('raw', base_schema, base_docs)
    extracted = IndexContext('extracted', [
        Field('id', FieldType.INTEGER),
        Field('status', FieldType.KEYWORD),
        Field('value', FieldType.INTEGER),
    ], extracted_docs)
    
    return CorrelatedIndexSet(
        base=base,
        variants={'extracted': extracted},
        relationships=[Relationship(
            type='spath',
            source_index='raw',
            target_index='extracted',
            mapping={'json_payload': ['status', 'value']},
        )]
    )
```

### 3. Property: Extraction Equivalence (~1 hour)

```python
# ppl_correctness/properties/extraction.py

class ExtractionEquivalenceProperty(Property):
    """spath/rex extraction ≡ querying pre-extracted index"""
    
    def check(self, corr_set: CorrelatedIndexSet, client) -> List[PropertyViolation]:
        rel = corr_set.relationships[0]  # assume one relationship for now
        
        if rel.type == 'spath':
            # source=raw | spath path=status input=json_payload | where status='ok' | stats count()
            # SHOULD EQUAL
            # source=extracted | where status='ok' | stats count()
            
            # Generate random tail (where, stats, sort, etc.)
            tail = self._generate_random_tail(corr_set.variants['extracted'])
            
            # Build extraction query
            extract_cmds = []
            for src_field, tgt_fields in rel.mapping.items():
                for tgt_field in tgt_fields:
                    extract_cmds.append(f"spath path={tgt_field} input={src_field}")
            
            # Cast strings → correct types (spath always outputs string)
            cast_cmds = []
            for field in corr_set.variants['extracted'].fields:
                if field.type in (FieldType.INTEGER, FieldType.DOUBLE):
                    cast_cmds.append(f"eval {field.name}=cast({field.name} as {field.type.value})")
            
            q1 = f"source={corr_set.base.name} | {' | '.join(extract_cmds)} | {' | '.join(cast_cmds)} | {tail}"
            q2 = f"source={corr_set.variants['extracted'].name} | {tail}"
            
            r1 = execute(client, q1)
            r2 = execute(client, q2)
            
            if not results_equal(r1, r2):
                return [PropertyViolation(
                    property='extraction_equivalence',
                    q1=q1, q2=q2, r1=r1, r2=r2
                )]
        
        return []
```

---

## Implementation Order (1 Day)

### Morning (4 hours)

**Hour 1:** Multi-index context + spath correlated pair
- `CorrelatedIndexSet` dataclass
- `generate_correlated_pair_spath()`
- Test: create both indices, verify data matches

**Hour 2:** Extraction equivalence property skeleton
- `ExtractionEquivalenceProperty.check()`
- Simple tail: just `stats count()`
- Test: spath on JSON vs. indexed fields match

**Hour 3:** Add WHERE tails
- Generate `where status='ok'` predicates on extracted fields
- Test: filtering works on both spath-extracted and indexed

**Hour 4:** Add STATS tails  
- Generate `stats avg(value) by status`
- Test: aggregations match

### Afternoon (4 hours)

**Hour 5:** Rex correlated pair
- `generate_correlated_pair_rex()` — `key1=val1, key2=val2` format
- Relationship type='rex', pattern='rex field=payload "key1=(?<key1>\w+)"'
- Test: rex extraction = indexed

**Hour 6:** Lookup correlated pair
- `generate_correlated_pair_lookup()` — two indices, one has extra fields
- Simulate: `source=base | lookup enrichment_table user_id` ≡ `source=joined`
- Inner join only (simplest)

**Hour 7:** Run all three on 100 iterations
- spath property: 100 random tails
- rex property: 100 random tails  
- lookup property: 100 random tails
- Log any failures

**Hour 8:** Coverage measurement + writeup
- Query SPL DB: what % of spath/rex/lookup patterns are now coverable?
- Document failures, file issues if real bugs
- Write summary: "With correlated schemas, X more patterns testable"

---

## Measuring Coverage Against SPL DB

### Query: Commands we can now test

```sql
-- Before: only basic commands
SELECT command, COUNT(*) as occurrences
FROM commands
WHERE command IN ('search', 'where', 'stats', 'sort', 'fields', 'eval', 'dedup', 'rename')
GROUP BY command
ORDER BY occurrences DESC;

-- After: add extraction/enrich commands  
SELECT command, COUNT(*) as occurrences
FROM commands
WHERE command IN ('search', 'where', 'stats', 'sort', 'fields', 'eval', 'dedup', 'rename',
                  'spath', 'rex', 'lookup')  -- NEW
GROUP BY command
ORDER BY occurrences DESC;
```

### Query: 2-command chains now testable

```sql
-- Chains involving spath/rex/lookup
SELECT chain, COUNT(*) as occurrences
FROM command_chains
WHERE length = 2
  AND (chain LIKE '%spath%' OR chain LIKE '%rex%' OR chain LIKE '%lookup%')
  AND skip_reason IS NULL  -- only valid queries
GROUP BY chain
ORDER BY occurrences DESC
LIMIT 20;

-- Example output:
-- search|spath: 45k
-- spath|search: 32k  
-- search|rex: 8.5k
-- rex|stats: 7k
-- search|lookup: 26k
```

### Query: What % of queries are now generatable?

```sql
-- Queries using ONLY commands we support
WITH supported_commands AS (
  SELECT 'search' as cmd UNION SELECT 'where' UNION SELECT 'stats' 
  UNION SELECT 'sort' UNION SELECT 'fields' UNION SELECT 'eval'
  UNION SELECT 'dedup' UNION SELECT 'rename' 
  UNION SELECT 'spath' UNION SELECT 'rex' UNION SELECT 'lookup'  -- NEW
),
query_commands AS (
  SELECT q.id, q.pattern, 
         GROUP_CONCAT(DISTINCT c.command) as used_commands
  FROM queries q
  JOIN commands c ON c.query_id = q.id
  WHERE q.skip_reason IS NULL
  GROUP BY q.id
)
SELECT 
  COUNT(*) as total_valid_queries,
  SUM(CASE WHEN NOT EXISTS (
    -- Check if any command is unsupported
    SELECT 1 FROM commands c2
    WHERE c2.query_id = qc.id
      AND c2.command NOT IN (SELECT cmd FROM supported_commands)
  ) THEN 1 ELSE 0 END) as fully_supported,
  ROUND(100.0 * SUM(CASE WHEN NOT EXISTS (
    SELECT 1 FROM commands c2
    WHERE c2.query_id = qc.id
      AND c2.command NOT IN (SELECT cmd FROM supported_commands)
  ) THEN 1 ELSE 0 END) / COUNT(*), 2) as pct_supported
FROM query_commands qc;

-- Expected: jumps from ~15% to ~40-50% with spath/rex/lookup
```

---

## Expected Outcomes (EOD)

### Deliverables
1. **Code:** `CorrelatedIndexSet`, 3 correlated generators (spath/rex/lookup), `ExtractionEquivalenceProperty`
2. **Data:** 300 test runs (100 per command type)
3. **Metrics:** Coverage % before/after (from SPL DB queries)
4. **Bugs:** 0-3 real bugs expected (based on 2% bug rate)

### Success Criteria
- All 3 extraction types run without crash
- At least 90% of iterations pass (some may hit known bugs)
- Coverage measurement shows >2x increase in testable patterns
- Clear path to generalizing (more relationship types, multi-hop chains)

### Follow-on (if time permits)
- **Eval equivalence:** With correlated schemas, `source=A | eval x=upper(y)` ≡ `source=B` where B has pre-computed upper
- **Multi-hop:** `source=raw | spath | rex` chains across multiple relationships
- **Incremental coverage tracking:** Daily cron → query SPL DB → report "X new patterns testable"

---

## Why This Unlocks Scale

**Before:** Each command needs custom property logic.
- spath property: ???
- rex property: ???  
- lookup property: ???
- = 3 separate implementations, no reuse

**After:** Extraction = generic transformation relationship.
- All three use `ExtractionEquivalenceProperty`
- New extraction types (grok, parse, flatten) = add relationship type
- Chain properties for free: `spath | rex` = compose relationships

**Incremental coverage:** SPL DB queries tell us:
- "Top 10 untested 2-chains" → prioritize next relationship type
- "Commands blocking 50k queries" → focus area
- "% increase per new command" → ROI tracking

Turns 6-month oneshot into **weekly sprints:** add one relationship type → measure coverage bump → repeat.

---

## Next Steps After Proof

If 1-day proof succeeds:

1. **Generalize property** — `TransformationEquivalenceProperty(relationship_type)` 
2. **Add relationship types** — flatten (arrays), convert (type casting), fillnull
3. **Multi-hop chains** — `raw | spath | rex | lookup` = compose 3 relationships
4. **Eval equivalence** — `eval x=f(y)` where f is any function, B has pre-computed x
5. **Coverage tracking** — daily SPL DB query → dashboard → Slack bot

**Tactical > strategic:** Ship one relationship type per week, measure coverage increase, adjust priorities based on SPL DB gaps.
