# Local operations

These instructions describe the local Docker Compose application. Use only synthetic training data. The application has no connection to a payment service, bank, email provider, or external identity provider.

## Prerequisites and first start

Install Docker Desktop and ensure its Linux container engine is running. Open PowerShell in the repository root. The first build needs internet access to download container images and dependencies.

```powershell
.\scripts\Init.ps1
.\scripts\Start.ps1
```

`Init.ps1` creates local configuration and generates a demo password. Its secret files stay under ignored local paths; do not add them to Git. `Start.ps1` builds the API image and then the web image sequentially to reduce simultaneous build memory, then starts Compose with health checks. Once the application is ready, open exactly:

```text
http://127.0.0.1:8096
```

The configured origin is exact. Opening `http://localhost:8096` uses a different browser origin and can cause mutation requests to fail the origin check.

Read `.local/LOCAL-CREDENTIALS.txt` for the generated password. Seeded accounts are `operator@recondesk.test` and `reviewer@recondesk.test`. They are fictional accounts in the local database, not real email addresses. The operator imports and proposes; the reviewer decides. Existing accounts are not reset when the service restarts. Editing the environment password after first boot does not rotate existing account hashes.

## Normal lifecycle

```powershell
# Build and start the local application.
.\scripts\Start.ps1

# Start existing images when source has not changed.
.\scripts\Start.ps1 -NoBuild

# Stop containers while retaining the database volume.
.\scripts\Stop.ps1
```

Compose owns the PostgreSQL volume. Normal stop/start retains users, sessions, imports, proposals, and audit events. There is no automatic database reset. Removing a Compose database volume is a separate destructive action; do not use it as an ordinary restart command.

## Configuration and ports

| Setting | Local value or role |
| --- | --- |
| Browser application | `http://127.0.0.1:8096` |
| Backend container listener | `8080`, reached through Nginx |
| PostgreSQL version | 17 |
| Local database host port | `127.0.0.1:5446` |
| Isolated test database host port | `127.0.0.1:5447` |
| `RECON_DEMO_PASSWORD` | Required secret used only when the user table is empty |
| `RECON_DB_URL`, `RECON_DB_USER`, `RECON_DB_PASSWORD` | Backend JDBC URL and database credentials |
| `RECON_APP_ORIGIN` | Exact allowed browser origin |
| `RECON_COOKIE_SECURE` | Secure cookie flag; local HTTP defaults to `false` |

Keep browser calls behind Nginx so the application and API have one origin. The backend connects to PostgreSQL through its JDBC configuration. Consult the actual Compose file and `backend/src/main/resources/application.properties` before changing ports or environment settings. Keep the browser origin and the server's allowed origin consistent.

The web image runs Nginx as the `nginx` user on port 8080. Its main configuration places the PID and temporary paths under `/tmp`; Compose provides temporary memory-backed paths and a read-only root filesystem. The server configuration supplies a Content Security Policy restricted to this origin, with scripts and styles served from the application.

The local scripts generate credentials rather than shipping a public default password. Keep `.local/` and the generated environment file ignored. Do not paste credentials, cookies, or CSRF tokens into issues, screenshots, or committed API examples.

## Verification

```powershell
.\scripts\Test.ps1
```

The default verification runs backend checks against a separate PostgreSQL instance, frontend unit checks and a production build, then cleanup of the isolated test database. It must not point at the persistent application database. Native backend checks need Java 17; frontend checks use Node.js 22 and npm. `scripts/Maven.ps1` bootstraps pinned Maven 3.9.16 and uses a local dependency cache, so a system Maven installation is not required.

To include browser checks, start the application first and have Microsoft Edge installed, then run:

```powershell
.\scripts\Test.ps1 -WithBrowser
```

Browser checks use the running application at `http://127.0.0.1:8096` and create synthetic runs there. Unlike backend integration tests, these runs are visible in the local persistent training workspace. The script reads the generated password into the process environment; it does not print it. Browser reports go under the frontend's ignored report directories. Playwright defaults to Edge locally and Chromium in CI; `RECON_BROWSER_CHANNEL` can select an installed browser channel.

The first test run needs network access for Maven and npm dependencies. If a command fails, read its actual output; a stopped container, unavailable dependency, or port collision is not a passing test. [VERIFICATION.md](VERIFICATION.md) records the commands and results observed for the delivered version. It also distinguishes any unavailable check from a successful one.

## Reproduce the SQL case study

```powershell
.\scripts\Benchmark.ps1
```

Keep port 5447 free and finish `Test.ps1` before starting this command. The benchmark starts a separate PostgreSQL instance under the `recondesk-benchmark` Compose project. It never connects to the persistent application database.

The [SQL script](../benchmarks/query-plan.sql) creates a temporary table with 250,000 generated rows, groups daily amounts for one account/date interval, and computes a running total using a window function. It records `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` before and after adding `(account_id, transaction_date) INCLUDE (amount)`. It reads the complete result on both sides and stops if they differ. The transaction rolls back; the temporary table and index do not remain.

The recorded pair on PostgreSQL 17.11 reports execution times of 94.499 ms before and 5.696 ms after, with identical query results. This is one local pair, with a result read before each plan measurement. The plans have different buffer-read/hit patterns, and cache state, planner estimates, hardware, and concurrent work can change the timing. These values do not measure API throughput or CSV import capacity.

The script replaces [the summary](benchmarks/README.md), [before.json](benchmarks/before.json), [after.json](benchmarks/after.json), and [result.json](benchmarks/result.json) with the new local evidence. Use the artifacts from the same run together. The saved plans show a sequential scan before the index and a bitmap index/heap scan after it; inspect those nodes and buffer counters as well as the headline timing.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Docker commands fail | Start Docker Desktop and confirm its engine is available |
| Port 8096 is occupied | Stop the process or another checkout using that port, then restart |
| Login succeeds but an action returns 403 | Use `http://127.0.0.1:8096`; refresh the session and CSRF token |
| Login returns 401 | Read the local credential file; a restart does not overwrite existing users |
| Login returns 429 | Wait for the login throttle interval before retrying |
| Session expires | Sign in again; sessions last 12 hours |
| Import returns 400 | Check exact header, field validation, data rows, and the named source error |
| Import returns 413 | Keep each source at or below 1 MiB; the 1,000-row limit is a separate validation rule |
| Import response was lost | Retry unchanged inputs with the original idempotency key |
| Case action returns 409 | Refresh case details, inspect the current state/version, and make a new deliberate action |
| New imports are refused at capacity | The local workspace permits 200 runs; no in-app deletion route is provided |
| Test database cannot start | Check port 5447 and Docker state; keep test and application databases separate |

For container-level diagnostics, run `docker compose ps` and `docker compose logs` from the repository root. Review logs locally before sharing them. The application should not log raw session tokens or passwords.

## Data and recovery limits

All source rows remain in PostgreSQL after a proposal is approved or rejected. Export provides run results for inspection; it is not a full database backup and does not contain every authentication or audit table.

This repository does not automate production backups, restore drills, credential rotation, retention enforcement, deployment, or remote monitoring. Preserve a database backup before any manual migration, reset, or volume change you cannot reverse. Do not assume a successful local health check proves those operational concerns have been solved.
