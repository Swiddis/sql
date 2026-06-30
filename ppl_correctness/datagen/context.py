"""
Index context and data generation for PPL testing.

An IndexContext contains:
- Index name
- Schema (field names and types)
- Sample data
"""

from dataclasses import dataclass, field
from typing import List, Dict, Any, Optional
from enum import Enum
import random
import json
from opensearchpy import OpenSearch


class FieldType(Enum):
    """PPL-supported field types"""
    INTEGER = "integer"
    LONG = "long"
    FLOAT = "float"
    DOUBLE = "double"
    BOOLEAN = "boolean"
    TEXT = "text"
    KEYWORD = "keyword"
    DATE = "date"
    IP = "ip"


@dataclass
class SubField:
    """Subfield definition for multi-field mappings (e.g., text with keyword)"""
    type: FieldType
    ignore_above: Optional[int] = None


@dataclass
class Field:
    """Schema field definition"""
    name: str
    type: FieldType
    nullable: bool = True
    is_array: bool = False  # Whether field holds array values
    subfields: Dict[str, SubField] = None  # e.g., {"keyword": SubField(KEYWORD, ignore_above=50)}
    nested_depth: int = 0  # 0 = flat field, >0 = nested (name uses dot notation)

    def __post_init__(self):
        if self.subfields is None:
            self.subfields = {}


@dataclass
class IndexContext:
    """Test index with schema and data"""
    name: str
    fields: List[Field]
    doc_count: int = 100

    def get_numeric_fields(self) -> List[Field]:
        """Return fields that support arithmetic operations"""
        return [f for f in self.fields if f.type in
                (FieldType.INTEGER, FieldType.LONG, FieldType.FLOAT, FieldType.DOUBLE)]

    def get_groupable_fields(self) -> List[Field]:
        """Return fields suitable for grouping (low cardinality)"""
        return [f for f in self.fields if f.type in
                (FieldType.KEYWORD, FieldType.BOOLEAN, FieldType.INTEGER)]

    def get_comparable_fields(self) -> List[Field]:
        """Return fields that support comparison operators"""
        return [f for f in self.fields if f.type != FieldType.TEXT]


@dataclass
class Relationship:
    """Describes transformation between two indices"""
    type: str  # 'spath', 'rex', 'lookup', 'cast'
    source_index: str
    target_index: str
    mapping: Dict[str, Any]  # source_field → target_field(s) or extraction config
    extraction_pattern: Optional[str] = None


@dataclass
class CorrelatedIndexSet:
    """Multiple indices with known relationships for property testing"""
    base: IndexContext
    variants: Dict[str, IndexContext]
    relationships: List[Relationship]


def generate_field_value(field: Field, rng: random.Random) -> Any:
    """Generate a random value for a field"""
    if field.nullable and rng.random() < 0.1:
        return None

    # Generate base scalar value
    base_value = None
    if field.type == FieldType.INTEGER:
        base_value = rng.randint(-1000, 1000)
    elif field.type == FieldType.LONG:
        base_value = rng.randint(-10000, 10000)
    elif field.type in (FieldType.FLOAT, FieldType.DOUBLE):
        base_value = rng.uniform(-100.0, 100.0)
    elif field.type == FieldType.BOOLEAN:
        base_value = rng.choice([True, False])
    elif field.type == FieldType.TEXT:
        words = ['apple', 'banana', 'cherry', 'date', 'elderberry']
        text = ' '.join(rng.choices(words, k=rng.randint(1, 3)))
        # For text fields with keyword subfields, sometimes generate long text
        if field.subfields and 'keyword' in field.subfields:
            subfield = field.subfields['keyword']
            if subfield.ignore_above and rng.random() < 0.3:
                # Generate text longer than ignore_above to trigger the bug
                text = text + " " + "x" * (subfield.ignore_above + 10)
        base_value = text
    elif field.type == FieldType.KEYWORD:
        base_value = rng.choice(['red', 'green', 'blue', 'yellow', 'purple'])
    elif field.type == FieldType.DATE:
        # Generate ISO date string
        year = rng.randint(2020, 2024)
        month = rng.randint(1, 12)
        day = rng.randint(1, 28)
        base_value = f"{year}-{month:02d}-{day:02d}"
    elif field.type == FieldType.IP:
        base_value = f"{rng.randint(1,255)}.{rng.randint(0,255)}.{rng.randint(0,255)}.{rng.randint(0,255)}"

    # Convert to array if needed
    if field.is_array and base_value is not None:
        # Generate array of 1-5 elements
        array_size = rng.randint(1, 5)
        if field.type in (FieldType.INTEGER, FieldType.LONG):
            return [rng.randint(-1000, 1000) for _ in range(array_size)]
        elif field.type in (FieldType.FLOAT, FieldType.DOUBLE):
            return [rng.uniform(-100.0, 100.0) for _ in range(array_size)]
        elif field.type == FieldType.KEYWORD:
            return rng.sample(['red', 'green', 'blue', 'yellow', 'purple', 'orange'], min(array_size, 6))
        else:
            # For other types, repeat the base value with variation
            return [base_value for _ in range(array_size)]

    return base_value


def create_test_index(client: OpenSearch, context: IndexContext, rng: random.Random):
    """Create and populate a test index"""
    # Build mapping with support for nested fields and subfields
    properties = {}

    for f in context.fields:
        field_mapping = {"type": f.type.value}

        # Add subfields (e.g., text with keyword)
        if f.subfields:
            field_mapping["fields"] = {}
            for subfield_name, subfield in f.subfields.items():
                subfield_mapping = {"type": subfield.type.value}
                if subfield.ignore_above is not None:
                    subfield_mapping["ignore_above"] = subfield.ignore_above
                field_mapping["fields"][subfield_name] = subfield_mapping

        # Handle nested fields (e.g., obj_0.value)
        if '.' in f.name:
            parts = f.name.split('.')
            current = properties
            for part in parts[:-1]:
                if part not in current:
                    current[part] = {"properties": {}}
                current = current[part]["properties"]
            current[parts[-1]] = field_mapping
        else:
            properties[f.name] = field_mapping

    mapping = {"mappings": {"properties": properties}}

    if client.indices.exists(index=context.name):
        client.indices.delete(index=context.name)

    client.indices.create(index=context.name, body=mapping)

    # Bulk insert documents
    documents = []
    for i in range(context.doc_count):
        source = {}

        for f in context.fields:
            value = generate_field_value(f, rng)

            # Handle nested fields (dot notation like "obj.nested.value")
            if '.' in f.name:
                parts = f.name.split('.')
                current = source
                for part in parts[:-1]:
                    if part not in current:
                        current[part] = {}
                    current = current[part]
                current[parts[-1]] = value
            else:
                source[f.name] = value

        doc = {
            "_index": context.name,
            "_id": i,
            "_source": source
        }
        documents.append(doc)

    from opensearchpy.helpers import bulk
    bulk(client, documents)
    client.indices.refresh(index=context.name)


def generate_correlated_pair_spath(
    client: OpenSearch,
    rng: random.Random,
    base_name: str = "spath_raw",
    doc_count: int = 100
) -> CorrelatedIndexSet:
    """Generate pair: base has JSON-as-text, variant has extracted fields."""

    # Extracted fields schema
    extracted_fields = [
        Field('id', FieldType.INTEGER, nullable=False),
        Field('status', FieldType.KEYWORD, nullable=False),
        Field('value', FieldType.INTEGER, nullable=False),
        Field('message', FieldType.KEYWORD, nullable=True),
    ]

    # Base has id + JSON text field
    base_fields = [
        Field('id', FieldType.INTEGER, nullable=False),
        Field('json_payload', FieldType.TEXT, nullable=False),
    ]

    # Generate correlated data
    base_docs = []
    extracted_docs = []

    for i in range(doc_count):
        status = rng.choice(['ok', 'error', 'pending', 'warning'])
        value = rng.randint(0, 1000)
        message = rng.choice(['success', 'failed', 'retry', None])

        # Base index has JSON string
        json_obj = {'status': status, 'value': value}
        if message:
            json_obj['message'] = message
        json_str = json.dumps(json_obj)
        base_docs.append({'id': i, 'json_payload': json_str})

        # Extracted index has individual fields
        extracted_docs.append({
            'id': i,
            'status': status,
            'value': value,
            'message': message
        })

    # Create base index
    base_ctx = IndexContext(base_name, base_fields, doc_count)
    base_mapping = {"mappings": {"properties": {
        "id": {"type": "integer"},
        "json_payload": {"type": "text"}
    }}}
    if client.indices.exists(index=base_name):
        client.indices.delete(index=base_name)
    client.indices.create(index=base_name, body=base_mapping)

    from opensearchpy.helpers import bulk
    bulk(client, [{"_index": base_name, "_id": i, "_source": doc} for i, doc in enumerate(base_docs)])
    client.indices.refresh(index=base_name)

    # Create extracted index
    extracted_name = f"{base_name}_extracted"
    extracted_ctx = IndexContext(extracted_name, extracted_fields, doc_count)
    extracted_mapping = {"mappings": {"properties": {
        "id": {"type": "integer"},
        "status": {"type": "keyword"},
        "value": {"type": "integer"},
        "message": {"type": "keyword"}
    }}}
    if client.indices.exists(index=extracted_name):
        client.indices.delete(index=extracted_name)
    client.indices.create(index=extracted_name, body=extracted_mapping)

    bulk(client, [{"_index": extracted_name, "_id": i, "_source": doc} for i, doc in enumerate(extracted_docs)])
    client.indices.refresh(index=extracted_name)

    return CorrelatedIndexSet(
        base=base_ctx,
        variants={'extracted': extracted_ctx},
        relationships=[Relationship(
            type='spath',
            source_index=base_name,
            target_index=extracted_name,
            mapping={'json_payload': ['status', 'value', 'message']},
        )]
    )


def generate_correlated_pair_rex(
    client: OpenSearch,
    rng: random.Random,
    base_name: str = "rex_raw",
    doc_count: int = 100
) -> CorrelatedIndexSet:
    """Generate pair: base has key=value text, variant has extracted fields."""

    # Extracted fields schema
    extracted_fields = [
        Field('id', FieldType.INTEGER, nullable=False),
        Field('user', FieldType.KEYWORD, nullable=False),
        Field('count', FieldType.INTEGER, nullable=False),
        Field('status', FieldType.KEYWORD, nullable=False),
    ]

    # Base has id + log-like text field
    base_fields = [
        Field('id', FieldType.INTEGER, nullable=False),
        Field('log_line', FieldType.TEXT, nullable=False),
    ]

    # Generate correlated data
    base_docs = []
    extracted_docs = []

    for i in range(doc_count):
        user = rng.choice(['alice', 'bob', 'charlie', 'david'])
        count = rng.randint(1, 100)
        status = rng.choice(['ok', 'error', 'pending'])

        # Base index has key=value format
        log_line = f"user={user}, count={count}, status={status}"
        base_docs.append({'id': i, 'log_line': log_line})

        # Extracted index has individual fields
        extracted_docs.append({
            'id': i,
            'user': user,
            'count': count,
            'status': status
        })

    # Create base index
    base_ctx = IndexContext(base_name, base_fields, doc_count)
    base_mapping = {"mappings": {"properties": {
        "id": {"type": "integer"},
        "log_line": {"type": "text"}
    }}}
    if client.indices.exists(index=base_name):
        client.indices.delete(index=base_name)
    client.indices.create(index=base_name, body=base_mapping)

    from opensearchpy.helpers import bulk
    bulk(client, [{"_index": base_name, "_id": i, "_source": doc} for i, doc in enumerate(base_docs)])
    client.indices.refresh(index=base_name)

    # Create extracted index
    extracted_name = f"{base_name}_extracted"
    extracted_ctx = IndexContext(extracted_name, extracted_fields, doc_count)
    extracted_mapping = {"mappings": {"properties": {
        "id": {"type": "integer"},
        "user": {"type": "keyword"},
        "count": {"type": "integer"},
        "status": {"type": "keyword"}
    }}}
    if client.indices.exists(index=extracted_name):
        client.indices.delete(index=extracted_name)
    client.indices.create(index=extracted_name, body=extracted_mapping)

    bulk(client, [{"_index": extracted_name, "_id": i, "_source": doc} for i, doc in enumerate(extracted_docs)])
    client.indices.refresh(index=extracted_name)

    return CorrelatedIndexSet(
        base=base_ctx,
        variants={'extracted': extracted_ctx},
        relationships=[Relationship(
            type='rex',
            source_index=base_name,
            target_index=extracted_name,
            mapping={'log_line': ['user', 'count', 'status']},
            extraction_pattern='key=value'
        )]
    )


def generate_correlated_pair_lookup(
    client: OpenSearch,
    rng: random.Random,
    base_name: str = "lookup_base",
    doc_count: int = 100
) -> CorrelatedIndexSet:
    """Generate pair: base + lookup table, variant has pre-joined data."""

    # Enrichment table (lookup table)
    enrichment_fields = [
        Field('user_id', FieldType.KEYWORD, nullable=False),
        Field('department', FieldType.KEYWORD, nullable=False),
        Field('role', FieldType.KEYWORD, nullable=False),
    ]

    # Base table
    base_fields = [
        Field('id', FieldType.INTEGER, nullable=False),
        Field('user_id', FieldType.KEYWORD, nullable=False),
        Field('action', FieldType.KEYWORD, nullable=False),
    ]

    # Joined table (base + enrichment)
    joined_fields = [
        Field('id', FieldType.INTEGER, nullable=False),
        Field('user_id', FieldType.KEYWORD, nullable=False),
        Field('action', FieldType.KEYWORD, nullable=False),
        Field('department', FieldType.KEYWORD, nullable=False),
        Field('role', FieldType.KEYWORD, nullable=False),
    ]

    # Generate enrichment table (small, static)
    users = ['alice', 'bob', 'charlie', 'david']
    departments = ['eng', 'sales', 'ops']
    roles = ['ic', 'manager', 'director']

    enrichment_docs = []
    user_lookup = {}
    for user in users:
        dept = rng.choice(departments)
        role = rng.choice(roles)
        enrichment_docs.append({
            'user_id': user,
            'department': dept,
            'role': role
        })
        user_lookup[user] = {'department': dept, 'role': role}

    # Generate base + joined data (correlated)
    base_docs = []
    joined_docs = []

    for i in range(doc_count):
        user_id = rng.choice(users)
        action = rng.choice(['login', 'logout', 'create', 'delete'])

        base_docs.append({
            'id': i,
            'user_id': user_id,
            'action': action
        })

        # Joined has enrichment data
        enrich = user_lookup[user_id]
        joined_docs.append({
            'id': i,
            'user_id': user_id,
            'action': action,
            'department': enrich['department'],
            'role': enrich['role']
        })

    # Create enrichment table
    enrich_name = f"{base_name}_enrichment"
    enrich_ctx = IndexContext(enrich_name, enrichment_fields, len(enrichment_docs))
    enrich_mapping = {"mappings": {"properties": {
        "user_id": {"type": "keyword"},
        "department": {"type": "keyword"},
        "role": {"type": "keyword"}
    }}}
    if client.indices.exists(index=enrich_name):
        client.indices.delete(index=enrich_name)
    client.indices.create(index=enrich_name, body=enrich_mapping)

    from opensearchpy.helpers import bulk
    bulk(client, [{"_index": enrich_name, "_id": i, "_source": doc} for i, doc in enumerate(enrichment_docs)])
    client.indices.refresh(index=enrich_name)

    # Create base index
    base_ctx = IndexContext(base_name, base_fields, doc_count)
    base_mapping = {"mappings": {"properties": {
        "id": {"type": "integer"},
        "user_id": {"type": "keyword"},
        "action": {"type": "keyword"}
    }}}
    if client.indices.exists(index=base_name):
        client.indices.delete(index=base_name)
    client.indices.create(index=base_name, body=base_mapping)

    bulk(client, [{"_index": base_name, "_id": i, "_source": doc} for i, doc in enumerate(base_docs)])
    client.indices.refresh(index=base_name)

    # Create joined index
    joined_name = f"{base_name}_joined"
    joined_ctx = IndexContext(joined_name, joined_fields, doc_count)
    joined_mapping = {"mappings": {"properties": {
        "id": {"type": "integer"},
        "user_id": {"type": "keyword"},
        "action": {"type": "keyword"},
        "department": {"type": "keyword"},
        "role": {"type": "keyword"}
    }}}
    if client.indices.exists(index=joined_name):
        client.indices.delete(index=joined_name)
    client.indices.create(index=joined_name, body=joined_mapping)

    bulk(client, [{"_index": joined_name, "_id": i, "_source": doc} for i, doc in enumerate(joined_docs)])
    client.indices.refresh(index=joined_name)

    return CorrelatedIndexSet(
        base=base_ctx,
        variants={
            'enrichment': enrich_ctx,
            'joined': joined_ctx
        },
        relationships=[Relationship(
            type='lookup',
            source_index=base_name,
            target_index=joined_name,
            mapping={'user_id': ['department', 'role']},
            extraction_pattern=f'{enrich_name} user_id'  # lookup table + join key
        )]
    )


def generate_contexts(
    host: str,
    num_contexts: int = 5,
    seed: Optional[int] = None,
    username: Optional[str] = None,
    password: Optional[str] = None
) -> List[IndexContext]:
    """Generate random test index contexts with diverse field types"""
    rng = random.Random(seed)

    if username and password:
        client = OpenSearch([host], http_auth=(username, password), use_ssl=True, verify_certs=True)
    else:
        client = OpenSearch([host])
    contexts = []

    for i in range(num_contexts):
        # Generate random schema with mix of field types
        num_fields = rng.randint(3, 8)
        fields = []

        for j in range(num_fields):
            field_type = rng.choice(list(FieldType))
            field_name = f"field_{j}"
            is_array = False
            subfields_dict = {}
            nested_depth = 0

            # ponytail: array generation disabled - bugs #5333 (GROUP BY explosion), other issues TBD
            # if field_type in (FieldType.INTEGER, FieldType.LONG, FieldType.KEYWORD) and rng.random() < 0.3:
            #     is_array = True
            #     field_name = f"array_{j}"

            # 20% chance of nested field
            if rng.random() < 0.2:
                depth = rng.randint(1, 2)
                nested_depth = depth
                if depth == 1:
                    field_name = f"obj_{j}.value"
                else:
                    field_name = f"obj_{j}.nested.value"

            # For text fields, 40% chance of keyword subfield with ignore_above
            if field_type == FieldType.TEXT and rng.random() < 0.4:
                ignore_above = rng.choice([50, 100, 256])
                subfields_dict = {
                    "keyword": SubField(FieldType.KEYWORD, ignore_above=ignore_above)
                }

            fields.append(Field(
                name=field_name,
                type=field_type,
                nullable=rng.choice([True, False]),
                is_array=is_array,
                subfields=subfields_dict,
                nested_depth=nested_depth
            ))

        context = IndexContext(
            name=f"test_index_{i}",
            fields=fields,
            doc_count=rng.randint(50, 200)
        )

        create_test_index(client, context, rng)
        contexts.append(context)

    return contexts
