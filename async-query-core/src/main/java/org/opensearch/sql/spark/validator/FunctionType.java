/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.spark.validator;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;

/**
 * Enum for defining and looking up SQL function type based on its name. Unknown one will be
 * considered as UDF (User Defined Function)
 */
@AllArgsConstructor
public enum FunctionType {
  AGGREGATE("Aggregate"),
  WINDOW("Window"),
  ARRAY("Array"),
  MAP("Map"),
  DATE_TIMESTAMP("Date and Timestamp"),
  JSON("JSON"),
  MATH("Math"),
  STRING("String"),
  CONDITIONAL("Conditional"),
  BITWISE("Bitwise"),
  CONVERSION("Conversion"),
  PREDICATE("Predicate"),
  CSV("CSV"),
  MISC("Misc"),
  GENERATOR("Generator"),
  UDF("User Defined Function");

  private final String name;

  private static final Map<FunctionType, Set<String>> FUNCTION_TYPE_TO_FUNCTION_NAMES_MAP =
      ImmutableMap.<FunctionType, Set<String>>builder()
          .put(
              AGGREGATE,
              Set.of(
                  "any",
                  "any_value",
                  "approx_count_distinct",
                  "approx_percentile",
                  "array_agg",
                  "avg",
                  "bit_and",
                  "bit_or",
                  "bit_xor",
                  "bitmap_construct_agg",
                  "bitmap_or_agg",
                  "bool_and",
                  "bool_or",
                  "collect_list",
                  "collect_set",
                  "corr",
                  "count",
                  "count_if",
                  "count_min_sketch",
                  "covar_pop",
                  "covar_samp",
                  "every",
                  "first",
                  "first_value",
                  "grouping",
                  "grouping_id",
                  "histogram_numeric",
                  "hll_sketch_agg",
                  "hll_union_agg",
                  "kurtosis",
                  "last",
                  "last_value",
                  "max",
                  "max_by",
                  "mean",
                  "median",
                  "min",
                  "min_by",
                  "mode",
                  "percentile",
                  "percentile_approx",
                  "regr_avgx",
                  "regr_avgy",
                  "regr_count",
                  "regr_intercept",
                  "regr_r2",
                  "regr_slope",
                  "regr_sxx",
                  "regr_sxy",
                  "regr_syy",
                  "skewness",
                  "some",
                  "std",
                  "stddev",
                  "stddev_pop",
                  "stddev_samp",
                  "sum",
                  "try_avg",
                  "try_sum",
                  "var_pop",
                  "var_samp",
                  "variance"))
          .put(
              WINDOW,
              Set.of(
                  "cume_dist",
                  "dense_rank",
                  "lag",
                  "lead",
                  "nth_value",
                  "ntile",
                  "percent_rank",
                  "rank",
                  "row_number"))
          .put(
              ARRAY,
              Set.of(
                  "array",
                  "array_append",
                  "array_compact",
                  "array_contains",
                  "array_distinct",
                  "array_except",
                  "array_insert",
                  "array_intersect",
                  "array_join",
                  "array_max",
                  "array_min",
                  "array_position",
                  "array_prepend",
                  "array_remove",
                  "array_repeat",
                  "array_union",
                  "arrays_overlap",
                  "arrays_zip",
                  "flatten",
                  "get",
                  "sequence",
                  "shuffle",
                  "slice",
                  "sort_array"))
          .put(
              MAP,
              Set.of(
                  "element_at",
                  "map",
                  "map_concat",
                  "map_contains_key",
                  "map_entries",
                  "map_from_arrays",
                  "map_from_entries",
                  "map_keys",
                  "map_values",
                  "str_to_map",
                  "try_element_at"))
          .put(
              DATE_TIMESTAMP,
              Set.of(
                  "add_months",
                  "convert_timezone",
                  "curdate",
                  "current_date",
                  "current_timestamp",
                  "current_timezone",
                  "date_add",
                  "date_diff",
                  "date_format",
                  "date_from_unix_date",
                  "date_part",
                  "date_sub",
                  "date_trunc",
                  "dateadd",
                  "datediff",
                  "datepart",
                  "day",
                  "dayofmonth",
                  "dayofweek",
                  "dayofyear",
                  "extract",
                  "from_unixtime",
                  "from_utc_timestamp",
                  "hour",
                  "last_day",
                  "localtimestamp",
                  "make_date",
                  "make_dt_interval",
                  "make_interval",
                  "make_timestamp",
                  "make_timestamp_ltz",
                  "make_timestamp_ntz",
                  "make_ym_interval",
                  "minute",
                  "month",
                  "months_between",
                  "next_day",
                  "now",
                  "quarter",
                  "second",
                  "session_window",
                  "timestamp_micros",
                  "timestamp_millis",
                  "timestamp_seconds",
                  "to_date",
                  "to_timestamp",
                  "to_timestamp_ltz",
                  "to_timestamp_ntz",
                  "to_unix_timestamp",
                  "to_utc_timestamp",
                  "trunc",
                  "try_to_timestamp",
                  "unix_date",
                  "unix_micros",
                  "unix_millis",
                  "unix_seconds",
                  "unix_timestamp",
                  "weekday",
                  "weekofyear",
                  "window",
                  "window_time",
                  "year"))
          .put(
              JSON,
              Set.of(
                  "from_json",
                  "get_json_object",
                  "json_array_length",
                  "json_object_keys",
                  "json_tuple",
                  "schema_of_json",
                  "to_json"))
          .put(
              MATH,
              Set.of(
                  "abs",
                  "acos",
                  "acosh",
                  "asin",
                  "asinh",
                  "atan",
                  "atan2",
                  "atanh",
                  "bin",
                  "bround",
                  "cbrt",
                  "ceil",
                  "ceiling",
                  "conv",
                  "cos",
                  "cosh",
                  "cot",
                  "csc",
                  "degrees",
                  "e",
                  "exp",
                  "expm1",
                  "factorial",
                  "floor",
                  "greatest",
                  "hex",
                  "hypot",
                  "least",
                  "ln",
                  "log",
                  "log10",
                  "log1p",
                  "log2",
                  "negative",
                  "pi",
                  "pmod",
                  "positive",
                  "pow",
                  "power",
                  "radians",
                  "rand",
                  "randn",
                  "random",
                  "rint",
                  "round",
                  "sec",
                  "shiftleft",
                  "sign",
                  "signum",
                  "sin",
                  "sinh",
                  "sqrt",
                  "tan",
                  "tanh",
                  "try_add",
                  "try_divide",
                  "try_multiply",
                  "try_subtract",
                  "unhex",
                  "width_bucket"))
          .put(
              STRING,
              Set.of(
                  "ascii",
                  "base64",
                  "bit_length",
                  "btrim",
                  "char",
                  "char_length",
                  "character_length",
                  "chr",
                  "concat",
                  "concat_ws",
                  "contains",
                  "decode",
                  "elt",
                  "encode",
                  "endswith",
                  "find_in_set",
                  "format_number",
                  "format_string",
                  "initcap",
                  "instr",
                  "lcase",
                  "left",
                  "len",
                  "length",
                  "levenshtein",
                  "locate",
                  "lower",
                  "lpad",
                  "ltrim",
                  "luhn_check",
                  "mask",
                  "octet_length",
                  "overlay",
                  "position",
                  "printf",
                  "regexp_count",
                  "regexp_extract",
                  "regexp_extract_all",
                  "regexp_instr",
                  "regexp_replace",
                  "regexp_substr",
                  "repeat",
                  "replace",
                  "right",
                  "rpad",
                  "rtrim",
                  "sentences",
                  "soundex",
                  "space",
                  "split",
                  "split_part",
                  "startswith",
                  "substr",
                  "substring",
                  "substring_index",
                  "to_binary",
                  "to_char",
                  "to_number",
                  "to_varchar",
                  "translate",
                  "trim",
                  "try_to_binary",
                  "try_to_number",
                  "ucase",
                  "unbase64",
                  "upper"))
          .put(CONDITIONAL, Set.of("coalesce", "if", "ifnull", "nanvl", "nullif", "nvl", "nvl2"))
          .put(
              BITWISE, Set.of("bit_count", "bit_get", "getbit", "shiftright", "shiftrightunsigned"))
          .put(
              CONVERSION,
              Set.of(
                  "bigint",
                  "binary",
                  "boolean",
                  "cast",
                  "date",
                  "decimal",
                  "double",
                  "float",
                  "int",
                  "smallint",
                  "string",
                  "timestamp",
                  "tinyint"))
          .put(PREDICATE, Set.of("isnan", "isnotnull", "isnull", "regexp", "regexp_like", "rlike"))
          .put(CSV, Set.of("from_csv", "schema_of_csv", "to_csv"))
          .put(
              MISC,
              Set.of(
                  "aes_decrypt",
                  "aes_encrypt",
                  "assert_true",
                  "bitmap_bit_position",
                  "bitmap_bucket_number",
                  "bitmap_count",
                  "current_catalog",
                  "current_database",
                  "current_schema",
                  "current_user",
                  "equal_null",
                  "hll_sketch_estimate",
                  "hll_union",
                  "input_file_block_length",
                  "input_file_block_start",
                  "input_file_name",
                  "java_method",
                  "monotonically_increasing_id",
                  "reflect",
                  "spark_partition_id",
                  "try_aes_decrypt",
                  "typeof",
                  "user",
                  "uuid",
                  "version"))
          .put(
              GENERATOR,
              Set.of(
                  "explode",
                  "explode_outer",
                  "inline",
                  "inline_outer",
                  "posexplode",
                  "posexplode_outer",
                  "stack"))
          .build();

  private static final Map<String, FunctionType> FUNCTION_NAME_TO_FUNCTION_TYPE_MAP =
      FUNCTION_TYPE_TO_FUNCTION_NAMES_MAP.entrySet().stream()
          .flatMap(
              entry -> entry.getValue().stream().map(value -> Map.entry(value, entry.getKey())))
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

  public static FunctionType fromFunctionName(String functionName) {
    return FUNCTION_NAME_TO_FUNCTION_TYPE_MAP.getOrDefault(functionName.toLowerCase(), UDF);
  }
}