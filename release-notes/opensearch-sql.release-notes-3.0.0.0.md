Compatible with OpenSearch and OpenSearch Dashboards Version 3.0.0.0

### Breaking Changes
* [v3.0.0] Bump gradle 8.10.2 / JDK23 on SQL plugin ([#3319](https://github.com/opensearch-project/sql/pull/3319))
* [v3.0.0] Remove SparkSQL support ([#3306](https://github.com/opensearch-project/sql/pull/3306))
* [v3.0.0] Remove opendistro settings and endpoints ([#3326](https://github.com/opensearch-project/sql/pull/3326))
* [v3.0.0] Deprecate SQL Delete statement ([#3337](https://github.com/opensearch-project/sql/pull/3337))
* [v3.0.0] Deprecate scroll API usage ([#3346](https://github.com/opensearch-project/sql/pull/3346))
* [v3.0.0] Deprecate OpenSearch DSL format ([#3367](https://github.com/opensearch-project/sql/pull/3367))
* [v3.0.0] Unified OpenSearch PPL Data Type ([#3345](https://github.com/opensearch-project/sql/pull/3345))
* [v3.0.0] Add datetime functions ([#3473](https://github.com/opensearch-project/sql/pull/3473))
* [v3.0.0] Support CAST function with Calcite ([#3439](https://github.com/opensearch-project/sql/pull/3439))

### Features
* PPL: Add `json` function and `cast(x as json)` function ([#3243](https://github.com/opensearch-project/sql/pull/3243))
* Framework of Calcite Engine: Parser, Catalog Binding and Plan Converter ([#3249](https://github.com/opensearch-project/sql/pull/3249))
* Enable Calcite by default and refactor all related ITs ([#3468](https://github.com/opensearch-project/sql/pull/3468))
* Make PPL execute successfully on Calcite engine ([#3258](https://github.com/opensearch-project/sql/pull/3258))
* Implement ppl join command with Calcite ([#3364](https://github.com/opensearch-project/sql/pull/3364))
* Implement ppl `IN` subquery command with Calcite ([#3371](https://github.com/opensearch-project/sql/pull/3371))
* Implement ppl relation subquery command with Calcite ([#3378](https://github.com/opensearch-project/sql/pull/3378))
* Implement ppl `exists` subquery command with Calcite ([#3388](https://github.com/opensearch-project/sql/pull/3388))
* Implement ppl scalar subquery command with Calcite ([#3392](https://github.com/opensearch-project/sql/pull/3392))
* Implement lookup command ([#3419](https://github.com/opensearch-project/sql/pull/3419))
* Support In expression in Calcite Engine ([#3429](https://github.com/opensearch-project/sql/pull/3429))
* Support ppl BETWEEN operator within Calcite ([#3433](https://github.com/opensearch-project/sql/pull/3433))
* Implement ppl `dedup` command with Calcite ([#3416](https://github.com/opensearch-project/sql/pull/3416))
* Support `parse` command with Calcite ([#3474](https://github.com/opensearch-project/sql/pull/3474))
* Support `TYPEOF` function with Calcite ([#3446](https://github.com/opensearch-project/sql/pull/3446))
* New output for explain endpoint with Calcite engine ([#3521](https://github.com/opensearch-project/sql/pull/3521))
* Make basic aggregation working ([#3318](https://github.com/opensearch-project/sql/pull/3318), [#3355](https://github.com/opensearch-project/sql/pull/3355))
* Push down project and filter operator into index scan ([#3327](https://github.com/opensearch-project/sql/pull/3327))
* Enable push down optimization by default ([#3366](https://github.com/opensearch-project/sql/pull/3366))
* Calcite enable pushdown aggregation ([#3389](https://github.com/opensearch-project/sql/pull/3389))
* Support multiple table and index pattern ([#3409](https://github.com/opensearch-project/sql/pull/3409))
* Support group by span over time based column with Span UDF ([#3421](https://github.com/opensearch-project/sql/pull/3421))
* Support nested field ([#3476](https://github.com/opensearch-project/sql/pull/3476))
* Execute Calcite PPL query in thread pool ([#3508](https://github.com/opensearch-project/sql/pull/3508))
* Support UDT for date, time, timestamp ([#3483](https://github.com/opensearch-project/sql/pull/3483))
* Support UDT for IP ([#3504](https://github.com/opensearch-project/sql/pull/3504))
* Support GEO_POINT type ([#3511](https://github.com/opensearch-project/sql/pull/3511))
* Add UDF interface ([#3374](https://github.com/opensearch-project/sql/pull/3374))
* Add missing text function ([#3471](https://github.com/opensearch-project/sql/pull/3471))
* Add string builtin functions ([#3393](https://github.com/opensearch-project/sql/pull/3393))
* Add math UDF ([#3390](https://github.com/opensearch-project/sql/pull/3390))
* Add condition UDFs ([#3412](https://github.com/opensearch-project/sql/pull/3412))
* Register OpenSearchTypeSystem to OpenSearchTypeFactory ([#3349](https://github.com/opensearch-project/sql/pull/3349))
* Enable update calcite setting through _plugins/_query/settings API ([#3531](https://github.com/opensearch-project/sql/pull/3531))
* Support alias type field ([#3538](https://github.com/opensearch-project/sql/pull/3538))
* Support UDT for BINARY ([#3549](https://github.com/opensearch-project/sql/pull/3549))
* Support metadata field ([#3445](https://github.com/opensearch-project/sql/pull/3445))
* Support CASE function ([#3558](https://github.com/opensearch-project/sql/pull/3558))

### Enhancements
* Add other functions to SQL query validator ([#3304](https://github.com/opensearch-project/sql/pull/3304))
* Improved patterns command with new algorithm ([#3263](https://github.com/opensearch-project/sql/pull/3263))
* Clean up syntax error reporting ([#3278](https://github.com/opensearch-project/sql/pull/3278))
* Support line comment and block comment in PPL ([#2806](https://github.com/opensearch-project/sql/pull/2806))
* Function framework refactoring ([#3522](https://github.com/opensearch-project/sql/pull/3522))
* Add SQLQuery Utils support for Vaccum queries ([#3269](https://github.com/opensearch-project/sql/pull/3269))

### Bug Fixes
* Fix execution errors caused by plan gap ([#3350](https://github.com/opensearch-project/sql/pull/3350))
* Support push down text field correctly ([#3376](https://github.com/opensearch-project/sql/pull/3376))
* Fix the join condition resolving bug introduced by IN subquery implementation ([#3377](https://github.com/opensearch-project/sql/pull/3377))
* Fix flaky tests ([#3456](https://github.com/opensearch-project/sql/pull/3456))
* Fix antlr4 parser issues ([#3492](https://github.com/opensearch-project/sql/pull/3492))
* Fix CSV handling of embedded crlf ([#3515](https://github.com/opensearch-project/sql/pull/3515))
* Fix return types of MOD and DIVIDE UDFs ([#3513](https://github.com/opensearch-project/sql/pull/3513))
* Fix varchar bug ([#3518](https://github.com/opensearch-project/sql/pull/3518))
* Fix text function IT for locate and strcmp ([#3482](https://github.com/opensearch-project/sql/pull/3482))
* Fix IT and CI, revert alias change ([#3423](https://github.com/opensearch-project/sql/pull/3423))
* Fix CalcitePPLJoinIT ([#3369](https://github.com/opensearch-project/sql/pull/3369))
* Keep aggregation in Calcite consistent with current PPL behavior ([#3405](https://github.com/opensearch-project/sql/pull/3405))
* Revert result ordering of `stats-by` ([#3427](https://github.com/opensearch-project/sql/pull/3427))
* Correct the precedence for logical operators ([#3435](https://github.com/opensearch-project/sql/pull/3435))
* Use correct timezone name ([#3517](https://github.com/opensearch-project/sql/pull/3517))
* Fix GET_FORMAT UDF ([#3543](https://github.com/opensearch-project/sql/pull/3543))
* Fix timestamp bug ([#3542](https://github.com/opensearch-project/sql/pull/3542))
* Fix issue 2489 ([#3442](https://github.com/opensearch-project/sql/pull/3442))
* New added commands should throw exception when calcite disabled ([#3571](https://github.com/opensearch-project/sql/pull/3571))
* Support parsing documents with flattened value ([#3577](https://github.com/opensearch-project/sql/pull/3577))

### Infrastructure
* Build integration test framework ([#3342](https://github.com/opensearch-project/sql/pull/3342))
* Set bouncycastle version inline ([#3469](https://github.com/opensearch-project/sql/pull/3469))
* Use entire shadow jar to fix IT ([#3447](https://github.com/opensearch-project/sql/pull/3447))
* Separate with/without pushdown ITs ([#3413](https://github.com/opensearch-project/sql/pull/3413))
* Only enable fallback for tests that need to fall back ([#3544](https://github.com/opensearch-project/sql/pull/3544))
* Remove beta1 qualifier ([#3589](https://github.com/opensearch-project/sql/pull/3589))

### Documentation
* Documentation for PPL new engine (V3) and limitations of 3.0.0 Beta ([#3488](https://github.com/opensearch-project/sql/pull/3488))

### Maintenance
* Build: Centralise dependencies version - Pt1 ([#3294](https://github.com/opensearch-project/sql/pull/3294))
* Remove dependency from async-query-core to datasources ([#2891](https://github.com/opensearch-project/sql/pull/2891))
* CVE-2024-57699 High: Fix json-smart vulnerability ([#3484](https://github.com/opensearch-project/sql/pull/3484))
* Adding new maintainer @qianheng-aws ([#3509](https://github.com/opensearch-project/sql/pull/3509))
* Bump SQL main to version 3.0.0.0-beta1 ([#3489](https://github.com/opensearch-project/sql/pull/3489))
* Merge feature/calcite-engine to main ([#3448](https://github.com/opensearch-project/sql/pull/3448))
* Merge main for OpenSearch 3.0 release ([#3434](https://github.com/opensearch-project/sql/pull/3434))
* Fix build due to phasing off SecurityManager usage in favor of Java Agent ([#3539](https://github.com/opensearch-project/sql/pull/3539))
* Using java-agent gradle plugin to phase off Security Manager in favor of Java-agent ([#3551](https://github.com/opensearch-project/sql/pull/3551))
