SELECT col0 , LAG(col0) OVER ( PARTITION BY col3 ORDER BY col1 ) LAG_col0 FROM "fewRowsAllData.parquet"