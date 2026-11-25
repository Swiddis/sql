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
| `<destination>` | Required | The pre-existing index or alias to write rows to. `collect` never creates it. It must name one index or alias: wildcards, comma-separated lists and `_all` are refused. The name is resolved the way the write resolves it (aliases and date math), and a destination that resolves to a system or hidden index (dot-prefixed, `index.hidden: true`, or a registered system index) is refused. |
| `id` | Optional | One or more columns whose values determine the document `_id`. Rows with the same values get the same `_id`, so the later row overwrites the earlier one and re-running the same query does not add duplicates. Without `id`, every row gets an auto-generated `_id` and every run appends. The `_id` is a hash of the listed fields' values in the order listed, so `id=a,b` and `id=b,a` produce different ids. A row where every `id` field is null fails the query with `400`, because such rows would all get the same id. The check runs row by row while writing, so it can fail after earlier batches (1,000 rows each) were written: those rows stay, and the error gives `rows_written`. Filter them with `where isnotnull(a)` or give them a value with `fillnull`. A null in only some of the `id` fields is allowed. Metadata columns such as `_id` are not written, but they are accepted as `id` fields. |
| `testmode` | Optional | When `true`, nothing is written: the query runs the same checks as a write on the destination and the input (the destination exists, is not a system or hidden index and has no write block, and the `id` fields are columns of the input) and returns the rows that would be written. Rows whose columns are all null are left out, as the write skips them. The preview is capped by `plugins.query.size_limit` (10,000 rows by default); the write itself is not capped. `testmode` does not check write permission: a user without write permission on the destination gets the preview, and the write fails with `403`. Default is `false`. |

Each parameter may be given once. A repeated parameter is a syntax error.

## Result

On success the result is a single row:

| Column | Description |
| --- | --- |
| `rows_written` | Number of rows the cluster accepted as index operations. Rows whose columns are all null are skipped and not counted. With `id`, a row that overwrites an existing document, or another row of the same run, still counts. A re-run reports the same number while the index grows by less. |
| `rows_failed` | Always `0` on success: any rejected row fails the whole query (see below). |

## Failures

A missing destination, a destination that is not an index, a refused system or hidden index, an `id` field that is not a column of the input, or a destination with a write block (`index.blocks.write`, `index.blocks.read_only` or `index.blocks.read_only_allow_delete`) fails the query before anything is written. The status is `400`, or `403` for a write block.

If the destination rejects any row (for example a mapping conflict, a strict mapping, a block set while the query runs, a closed index, the total-fields limit, or a missing write permission), the query fails. The error has the status of the first rejected row (for example `400` for a mapping conflict, `403` for a missing permission or a write block). Its message gives `rows_written=<n>` (rows written before the failure), `rows_failed=<n>` (rows rejected in the failing bulk request) and the first rejection reason.

If the whole write request fails, for example because the destination is deleted or gets a cluster block while the query runs, the query fails with that error's status (`404` for a missing index, `403` or `429` for a block). The message of such an error has no `rows_written` count. These errors are returned without the `java.sql.SQLException` prefix, with the exception's own type (`OpenSearchStatusException`, `OpenSearchRejectedExecutionException`, `ClusterBlockException`); other exceptions raised inside the query engine, such as a failure to execute an unrelated query, keep the prefix and `RuntimeException`.

Rows written before the failure stay in the destination: `collect` does not roll back. With `id`, re-running the query after fixing the cause overwrites those rows instead of duplicating them.

Rejections because the cluster is overloaded (HTTP 429) are retried with exponential backoff, both when single rows are rejected and when the whole bulk request is rejected. The retries for rejected rows wait at most 5 seconds in total per batch, and the retries for a rejected request wait about 5 seconds in total. The wait happens on the query's worker thread. If the cluster is still rejecting writes after the retries, the query fails with `429`.

## Value conversion

- Timestamps are written as ISO-8601 UTC strings (`2026-11-23T10:00:00Z`), which a `date` field accepts with its default format. Dates are written as `2026-11-23`. A destination date field with a custom `format` (for example `yyyy/MM/dd`) rejects these unless its format also accepts ISO-8601, such as `strict_date_optional_time||yyyy/MM/dd`. `collect` does not read the destination's `format`.
- `ip` values are written as strings, `geo_point` values as `{"lat": ..., "lon": ...}`, binary values as base64, and intervals as ISO-8601 durations or periods in the interval's own unit (`INTERVAL 5 MINUTE` is `PT5M`, `INTERVAL 2 DAY` is `P2D`, `INTERVAL 1 MONTH` is `P1M`). Map `interval` values to a `keyword` field.
- Object and `nested` fields are written once, as the object (or array of objects), not also as dotted keys. Timestamps inside them are written in ISO-8601 like top-level ones, also when the object is selected alone (`fields h`). The member's type is read from the source index, so this holds when the object comes from one source column; for an object built by a query (for example with `eval`) a timestamp inside it keeps the PPL form `yyyy-MM-dd HH:mm:ss`.
- Columns are written under the names the query gives them: after `rename x as y` the field is `y`.
- `NaN` is written as the string `"NaN"`, which makes a dynamically mapped destination field `text`. A number that does not fit the type OpenSearch infers (for example `1e300` in a dynamic index) is rejected by the destination. Map such fields explicitly.
- Metadata columns (`_id`, `_index`, ...) are not written: `fields _id, x` writes only `x`.

## Permissions

The user running `collect` needs read permission on the source, the `indices:data/write/bulk` cluster permission, write permission on the destination, and `indices:admin/mappings/get` and `indices:monitor/settings/get` on the destination (used to check it before writing). Write commands can be turned off for the whole cluster with the `plugins.ppl.write_commands.enable` setting (see [settings](../admin/settings.md)). When it is `false`, `collect`, `collect ... testmode=true` and `explain` of a `collect` query are refused with `400`, and the message naming the setting is in both `reason` and `details`.

`collect` reads the source as the calling user, so document-level and field-level security on the source decide what is written: rows the user cannot see are not written, and fields hidden from the user are absent from the written documents. Security on the source does not carry over to the destination: anyone who can read the destination sees every row and field the writing user could see.

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

- The destination index must already exist. `testmode` does not check whether it is closed or whether the user may write to it: a closed destination passes `testmode` and fails the write with `400`, and a destination the user cannot write to fails the write with `403`.
- Writes are not refreshed. A `source=<destination>` query right after `collect` can miss the new rows until the index refreshes (by default within one second), or until you call `_refresh`.
- The request is synchronous: the query returns after the last row is written. Large inputs hold the request open for the whole write (about 8.5 seconds for 300,000 two-column rows on one node).
- `rows_written` is the number of rows accepted as index operations, not the number of documents added (see Result).
- A failure partway through, or a node failure, leaves the rows written so far in the destination.
- `collect` is not covered by the doctest harness, because its examples need a pre-existing destination index. `CalcitePPLCollectIT` tests it end to end.
