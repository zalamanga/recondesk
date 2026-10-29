# Local SQL plan measurement

Measured at: 2026-10-29T16:23:22.4830373+07:00
PostgreSQL: 17.11
Synthetic input rows: 250000

| Measurement | Before index | After index |
| --- | ---: | ---: |
| Execution time (ms) | 94.499 | 5.696 |
| Planning time (ms) | 0.496 | 0.551 |

The same query groups daily amounts and calculates a running total with a window function. The only schema difference is the composite account/date index including amount. The complete result was compared byte-for-byte and is identical.

This is one paired local measurement, with a result read before each EXPLAIN to warm data. It is not a production throughput benchmark or a universal speedup claim. PostgreSQL estimates, cache state, hardware and concurrent work affect timings. Read the complete plans and buffer counters in before.json and after.json.

The table is temporary, contains generated synthetic data and is rolled back. The script creates a separate temporary PostgreSQL instance and never connects to the application database.

Reproduce on Windows: .\scripts\Benchmark.ps1 (port 5447 must be free). Generated results replace this evidence with the new local measurement.

Reference: [PostgreSQL EXPLAIN](https://www.postgresql.org/docs/17/using-explain.html).
