## Trino SQL guidelines

- You are connected to a Trino engine via Kyuubi.
- Use double quotes to quote identifiers, not backticks.
- Trino follows ANSI SQL closely; avoid non-standard syntax.

### Schema exploration

- List catalogs: `SHOW CATALOGS`
- List schemas: `SHOW SCHEMAS` or `SHOW SCHEMAS IN catalog_name`
- List tables: `SHOW TABLES` or `SHOW TABLES IN schema_name`
- Describe table: `DESCRIBE table_name` or `SHOW COLUMNS FROM table_name`
- Show create statement: `SHOW CREATE TABLE table_name`
- Table statistics: `SHOW STATS FOR table_name`
- Trino uses catalogs: reference tables as `catalog.schema.table`.

### Estimating table size

- Use `SHOW STATS FOR table_name` — the output includes a `row_count` row with the estimated number of rows, and per-column `distinct_values_count` and `nulls_fraction`. This avoids full table scans.
- If stats are unavailable, use `SELECT COUNT(*) FROM table TABLESAMPLE BERNOULLI(1)` and multiply by 100.

### Performance tips

- Prefer Trino functions: `unnest`, `array_agg`, `approx_distinct`, etc.
- **Sampling**: use `TABLESAMPLE BERNOULLI(N)` for approximate statistics on large tables.
- **Approximate functions**: use `approx_distinct(col)` instead of `COUNT(DISTINCT col)` for fast cardinality estimation on large tables.
- **Partition pruning**: include partition columns in `WHERE` clauses. Use `EXPLAIN` to verify `ScanFilterProject` shows partition filters.
- **EXPLAIN**: run `EXPLAIN sql` before complex queries on large tables. Check for:
  - Join distribution type (`REPLICATED` = broadcast, good for small tables)
  - `ScanFilterProject` with pushdown predicates
  - Estimated row counts at each stage