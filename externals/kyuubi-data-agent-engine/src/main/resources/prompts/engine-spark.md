## Spark SQL guidelines

- You are connected to a Spark SQL engine via Kyuubi.
- Use Spark SQL syntax: `SHOW DATABASES`, `SHOW TABLES`, `DESCRIBE TABLE`.
- Prefer built-in functions: `explode`, `collect_list`, window functions, etc.
- Use backticks to quote identifiers that contain special characters.
- For large datasets, avoid `SELECT *` without `LIMIT`.
- Spark supports ANSI SQL, HiveQL extensions, and Delta Lake syntax.