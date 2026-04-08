# Example Queries and Output
This document demonstrates the execution of various SQL-like commands supported by the `Column-Store-Data-Storage-System`. The queries showcase database creation, table creation with constraints, data insertion, querying, updating, and deletion.

We ran the interactive CLI (`java cdb.client.CLIClient`) and piped the following queries:

### Input Queries File (`test_queries.txt`)
```sql
CREATE DATABASE testdb
SHOW DATABASES
USE DATABASE testdb
CREATE TABLE sensors (id INT PRIMARY_KEY, temp DOUBLE, humidity DOUBLE, is_active BOOLEAN)
INSERT INTO sensors VALUES ('1', '25.5', '60.0', 'true')
INSERT INTO sensors VALUES ('2', '26.0', '58.5', 'true')
INSERT INTO sensors VALUES ('3', '24.1', '62.0', 'false')
SELECT id, temp, humidity, is_active FROM sensors
UPDATE sensors SET temp = 25.8 WHERE id = 1
SELECT id, temp, humidity, is_active FROM sensors
DELETE FROM sensors WHERE id = 3
SELECT id, temp, humidity, is_active FROM sensors
EXIT
```

### Console Output
The client successfully executed all commands. The output accurately reflects the schema creation, data insertion, and subsequent modifications:

```text
CDB > Database 'testdb' created successfully.

CDB > +--------------------------+
| Databases                |
+--------------------------+
| testdb                  |
| companydb               |
+--------------------------+

CDB > Switched to database 'testdb'.

CDB [testdb] > Table sensors created successfully.

CDB [testdb] > 1 row inserted.

CDB [testdb] > 1 row inserted.

CDB [testdb] > 1 row inserted.

CDB [testdb] > id       temp    humidity        is_active
----------------------------------------
1       25.5    60.0    true
2       26.0    58.5    true
3       24.1    62.0    false
(3 rows)

CDB [testdb] > 1 rows updated.

CDB [testdb] > id       temp    humidity        is_active
----------------------------------------
1       25.8    60.0    true
2       26.0    58.5    true
3       24.1    62.0    false
(3 rows)

CDB [testdb] > 1 rows deleted.

CDB [testdb] > id       temp    humidity        is_active
----------------------------------------
1       25.8    60.0    true
2       26.0    58.5    true
(2 rows)

CDB [testdb] > Goodbye.
```

## Functionality Status
All CRUD (Create, Read, Update, Delete) and DDL (Data Definition Language) functionalities are confirmed to be intact and behaving according to the system's specifications. The `BinaryStorageEngine` enforces numeric/boolean types and effectively processes constraints like `PRIMARY_KEY`.
