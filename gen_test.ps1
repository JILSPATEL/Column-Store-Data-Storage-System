$OutputEncoding = [Console]::Utf8
"CREATE DATABASE big_test" | Out-File -Encoding UTF8 big_queries.txt
"USE DATABASE big_test" | Out-File -Encoding UTF8 big_queries.txt -Append
"CREATE TABLE hitable (id INT PRIMARY_KEY)" | Out-File -Encoding UTF8 big_queries.txt -Append
for ($i=1; $i -le 1005; $i++) {
   "INSERT INTO hitable VALUES ('$i')" | Out-File -Encoding UTF8 big_queries.txt -Append
}
"SELECT id FROM hitable WHERE id = 500" | Out-File -Encoding UTF8 big_queries.txt -Append
"SHOW BITMAP INDEX hitable" | Out-File -Encoding UTF8 big_queries.txt -Append
"EXIT" | Out-File -Encoding UTF8 big_queries.txt -Append
