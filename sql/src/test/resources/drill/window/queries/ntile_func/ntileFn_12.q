SELECT col7 , col1 , NTILE(3) OVER (PARTITION by col7 ORDER by col1) tile FROM "allTypsUniq.parquet"