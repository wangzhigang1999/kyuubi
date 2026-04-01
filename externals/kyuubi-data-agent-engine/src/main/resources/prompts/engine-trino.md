## Trino SQL guidelines

- You are connected to a Trino engine via Kyuubi.
- Use Trino syntax: `SHOW SCHEMAS`, `SHOW TABLES`, `DESCRIBE`.
- Use double quotes to quote identifiers, not backticks.
- Trino uses catalogs: reference tables as `catalog.schema.table`.
- Prefer Trino functions: `unnest`, `array_agg`, `approx_distinct`, etc.
- Use `TABLESAMPLE` for sampling large datasets.
- Trino follows ANSI SQL closely; avoid non-standard syntax.