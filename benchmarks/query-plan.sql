\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE bench_transactions (
    id bigint NOT NULL,
    account_id integer NOT NULL,
    transaction_date date NOT NULL,
    amount numeric(16,2) NOT NULL,
    reference varchar(32) NOT NULL
) ON COMMIT DROP;
INSERT INTO bench_transactions
SELECT g, (g % 500)::integer,
       DATE '2026-01-01' + ((g / 500) % 365)::integer,
       ((g % 100000)::numeric / 100)::numeric(16,2),
       'SIM-' || lpad(g::text, 9, '0')
FROM generate_series(1, 250000) AS g;
ANALYZE bench_transactions;
\echo BEGIN_ENVIRONMENT
SELECT json_build_object('serverVersion', current_setting('server_version'), 'rows', count(*)) FROM bench_transactions;
\echo END_ENVIRONMENT

\echo BEGIN_RESULT_BEFORE
WITH daily AS (
    SELECT transaction_date, sum(amount) AS total, count(*) AS entries
    FROM bench_transactions
    WHERE account_id = 42 AND transaction_date >= DATE '2026-01-01' AND transaction_date < DATE '2026-04-01'
    GROUP BY transaction_date
), report AS (
    SELECT transaction_date, total, entries, sum(total) OVER (ORDER BY transaction_date) AS running_total
    FROM daily ORDER BY transaction_date
)
SELECT jsonb_agg(to_jsonb(report) ORDER BY transaction_date) FROM report;
\echo END_RESULT_BEFORE

\echo BEGIN_PLAN_BEFORE
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
WITH daily AS (
    SELECT transaction_date, sum(amount) AS total, count(*) AS entries
    FROM bench_transactions
    WHERE account_id = 42 AND transaction_date >= DATE '2026-01-01' AND transaction_date < DATE '2026-04-01'
    GROUP BY transaction_date
)
SELECT transaction_date, total, entries, sum(total) OVER (ORDER BY transaction_date) AS running_total
FROM daily ORDER BY transaction_date;
\echo END_PLAN_BEFORE

CREATE INDEX bench_account_date_amount ON bench_transactions (account_id, transaction_date) INCLUDE (amount);
ANALYZE bench_transactions;

\echo BEGIN_RESULT_AFTER
WITH daily AS (
    SELECT transaction_date, sum(amount) AS total, count(*) AS entries
    FROM bench_transactions
    WHERE account_id = 42 AND transaction_date >= DATE '2026-01-01' AND transaction_date < DATE '2026-04-01'
    GROUP BY transaction_date
), report AS (
    SELECT transaction_date, total, entries, sum(total) OVER (ORDER BY transaction_date) AS running_total
    FROM daily ORDER BY transaction_date
)
SELECT jsonb_agg(to_jsonb(report) ORDER BY transaction_date) FROM report;
\echo END_RESULT_AFTER

\echo BEGIN_PLAN_AFTER
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
WITH daily AS (
    SELECT transaction_date, sum(amount) AS total, count(*) AS entries
    FROM bench_transactions
    WHERE account_id = 42 AND transaction_date >= DATE '2026-01-01' AND transaction_date < DATE '2026-04-01'
    GROUP BY transaction_date
)
SELECT transaction_date, total, entries, sum(total) OVER (ORDER BY transaction_date) AS running_total
FROM daily ORDER BY transaction_date;
\echo END_PLAN_AFTER
ROLLBACK;
