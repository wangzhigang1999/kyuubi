## MySQL guidelines

- You are connected to a MySQL database.
- Use backticks to quote identifiers that contain special characters or reserved words.

### Schema exploration

- List databases: `SHOW DATABASES`
- Switch database: `USE database_name`
- List tables: `SHOW TABLES` or `SHOW TABLES FROM database_name`
- Describe table: `DESCRIBE table_name` or `SHOW CREATE TABLE table_name`
- Show indexes: `SHOW INDEX FROM table_name`

### Estimating table size

- Use `SHOW TABLE STATUS LIKE 'table_name'` — the `Rows` column gives an approximate row count (InnoDB estimate, not exact) and `Data_length` gives the data size in bytes. This is instant and avoids full table scans.
- For more detail: `SELECT TABLE_NAME, TABLE_ROWS, DATA_LENGTH FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'db_name'` lists all tables with estimated row counts in one query.

### Performance tips

- **Index awareness**: use `SHOW INDEX FROM table_name` to check available indexes before writing WHERE clauses. Filter on indexed columns when possible.
- **EXPLAIN**: run `EXPLAIN sql` before complex queries. Check for:
  - `type`: `ALL` (full scan, bad) vs `ref`/`range`/`eq_ref` (index used, good)
  - `rows`: estimated rows scanned — high values on filtered queries indicate missing indexes
  - `Extra`: `Using filesort` or `Using temporary` indicate expensive operations
- **No TABLESAMPLE**: MySQL does not support `TABLESAMPLE`. For sampling, use `SELECT ... ORDER BY RAND() LIMIT N` (slow on large tables) or `WHERE id % 100 = 0` if an auto-increment column exists.
- **Approximate distinct**: MySQL has no built-in approximate distinct function. For large tables, consider `SELECT COUNT(*) FROM (SELECT DISTINCT col FROM table LIMIT 10000) t` as a bounded approximation.
- Prefer `LIMIT` on all exploratory queries.
