# collect

> **Experimental**: `collect` is experimental. Its syntax and result format may change in a later release.

The `collect` command writes the rows of the pipeline into a pre-existing destination index. It returns one row with the number of rows written instead of the rows themselves.

`collect` must be the last command of the query. It is refused anywhere else, including inside a subsearch such as `append [ ... ]`.

## Syntax

The `collect` command has the following syntax:

```syntax
collect index=<destination> [id=<field>[, <field>...]] [testmode=<boolean>]
```

## Parameters

The `collect` command supports the following parameters.

| Parameter | Required/Optional | Description |
| --- | --- | --- |
| `<destination>` | Required | The pre-existing index to write rows to. `collect` never creates it. A system or hidden (dot-prefixed) index is refused. |
| `id` | Optional | One or more columns whose values determine the document `_id`. Rows with the same values get the same `_id`, so the later row overwrites the earlier one and re-running the same query does not add duplicates. Without `id`, every row gets an auto-generated `_id` and every run appends. |
| `testmode` | Optional | When `true`, nothing is written: the query returns the rows that would be written. Default is `false`. |

## Result

On success the result is a single row:

| Column | Description |
| --- | --- |
| `rows_written` | Number of documents written. Rows whose columns are all null are skipped and not counted. |
| `rows_failed` | Always `0` on success: any rejected row fails the whole query (see below). |

## Failures

If the destination rejects any row (for example a mapping conflict, a strict mapping, a write block, a closed index, the total-fields limit, or a missing write permission), the query fails. The error has the status of the first rejected row (for example `400` for a mapping conflict, `403` for a missing permission or a write block). Its message gives `rows_written=<n>` (rows written before the failure), `rows_failed=<n>` (rows rejected in the failing bulk request) and the first rejection reason.

Rows written before the failure stay in the destination: `collect` does not roll back. With `id`, re-running the query after fixing the cause overwrites those rows instead of duplicating them.

Rejections because the cluster is overloaded (HTTP 429) are retried with exponential backoff before they count as failures.

## Value conversion

- Timestamps are written as ISO-8601 UTC strings (`2026-11-23T10:00:00Z`), which a `date` field accepts with its default format.
- `ip` values are written as strings, `geo_point` values as `{"lat": ..., "lon": ...}`, binary values as base64, intervals as ISO-8601 durations (`PT5M`), and structs and arrays recursively.

## Permissions

The user running `collect` needs read permission on the source, the `indices:data/write/bulk` cluster permission, and write permission on the destination. Document-level security applies to what `collect` reads, not to the destination: anyone who can read the destination sees the rows the writing user could see.

## Example 1: Write a summary with an idempotent id

The following query counts errors per host and writes one document per host. Running it again overwrites the same documents:

```ppl ignore
source=otellogs
| where severityText = 'ERROR'
| stats count() as errors by `resource.attributes.host.name`
| collect index=errors_by_host id=`resource.attributes.host.name`
```

The query returns one row:

```text
fetched rows / total rows = 1/1
+--------------+-------------+
| rows_written | rows_failed |
|--------------+-------------|
| 3            | 0           |
+--------------+-------------+
```

## Example 2: Dry run with testmode

The following query previews the rows that would be written, without writing them:

```ppl ignore
source=otellogs
| where severityText = 'ERROR'
| fields severityText, body
| collect index=errors_summary testmode=true
```

## Limitations

- The destination index must already exist.
- The request is synchronous: the query returns after the last row is written. Large inputs hold the request open for the whole write (about 8.5 seconds for 300,000 two-column rows on one node).
- A failure partway through, or a node failure, leaves the rows written so far in the destination.
- `collect` is not covered by the doctest harness, because its examples need a pre-existing destination index. `CalcitePPLCollectIT` tests it end to end.
