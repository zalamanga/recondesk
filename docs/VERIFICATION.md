# Verification record

Date: 2026-10-29. Checks ran against this project's final application sources on Windows, Java 17, Node 22 and Docker Desktop. All imported transactions were synthetic. This record reports observed behavior rather than a production-readiness claim.

## Local results

| Check | Observed result |
| --- | --- |
| `scripts/Start.ps1` | Backend and frontend multi-stage images built; PostgreSQL, API and Nginx reported healthy. |
| `scripts/Test.ps1` | 34 Java tests passed: 24 CSV tests and 10 real PostgreSQL/HTTP integration tests; zero failures, errors or skips. |
| Frontend tests inside `Test.ps1` | Two Vitest files, 11 tests passed. |
| Frontend production build | TypeScript and Vite passed; 35 modules transformed. |
| `npm audit --audit-level=high` | Zero reported vulnerabilities for the locked frontend dependency tree at verification time. |
| PowerShell syntax | All seven published PowerShell scripts parsed successfully. |
| `npm run test:e2e` against Docker | Three browser tests passed together in Microsoft Edge, zero skips, 31.2 seconds for that run. |
| Script environment isolation | Test was invoked with an intentionally different inherited test password; it used the generated local configuration consistently and restored the original environment and working directory. The temporary database was removed afterward. |
| Screenshots and UI review | Four actual captures; six run viewport widths from 320 to 1440 px; zero uncaught page errors in the capture and primary-agent review. See [the complete UI gate](UX-REVIEW.md). |
| Text contrast | 269 rendered combinations checked in the stated UI scope; minimum 4.562:1, meeting the applicable AA text threshold. |
| SQL plan study | 250,000 temporary synthetic rows; query results identical before/after the index. Saved [plans and measurement limits](benchmarks/README.md). |
| Publication scan | 82 intended files, no generated local passwords or private-key/token patterns found, no broken local Markdown links; both public sample copies match their root originals. |

The native test command starts its own temporary PostgreSQL instance on port 5447. It does not connect to the application's persistent database on port 5446. The application was stopped during the final native test run to reduce memory use; its database volume was retained.

## What the tests exercise

[CsvSourcesTest](../backend/src/test/java/com/recondesk/CsvSourcesTest.java) covers strict headers and quoting, UTF-8/BOM handling, malformed input, row and byte bounds, decimal limits, date validity and the accepted historical year range.

[PostgresIntegrationTest](../backend/src/test/java/com/recondesk/PostgresIntegrationTest.java) runs the actual HTTP server and PostgreSQL migration. Its cases exercise authentication and route normalization, cookies/CSRF/origin checks, roles, decimal-string results, matching and duplicate evidence, atomic invalid imports, idempotency and conflicting imports, independent review, stale/concurrent decisions, audit events, pagination, filtering and CSV export. The suite uses a deliberately disposable database.

Frontend unit tests exercise response/error handling and decimal-preserving display/validation helpers. The [browser suite](../frontend/tests/workflow.spec.ts) exercises:

1. A real committed import with the first browser response dropped, followed by the same-identity HTTP 200 retry. It then tests a stale operator tab, rejection, revision, approval, history tabs, filtering, export and audit.
2. A 390 px reviewer view, read-only import behavior, sample download, sign-out and keyboard login traversal.
3. An invalid partner file with no partial run, followed by corrected files and a new import identity.

For the first test's fault injection, Playwright reconstructs the same multipart name and selected fixture bytes through the authenticated browser context before aborting the intercepted browser request. This avoids Playwright's intercepted multipart replay losing file bytes. The commit and retry go to the real application; the fixture does not fabricate a success payload. Private failure traces are ignored by Git because they can contain login input values.

The additional primary-agent interaction review checks more filter, pagination, preset, focus, duplicate, loading, empty, service-error and reauthentication behavior. Test-only response delays/errors were used to expose states; the product implementation was not patched to manufacture screenshots.

## Reproduce

From the repository root in PowerShell:

```powershell
.\scripts\Init.ps1
.\scripts\Test.ps1
.\scripts\Start.ps1
.\scripts\Test.ps1 -WithBrowser
.\scripts\Benchmark.ps1
.\scripts\Stop.ps1
```

The browser command requires Edge locally, or a Playwright browser configured as described in [operations](OPERATIONS.md). It writes synthetic runs into the locally running application. The benchmark requires port 5447 to be free, creates a separate temporary PostgreSQL project, rolls back the generated table and removes that temporary instance. It replaces the committed measurement files with the new local results; timings will vary.

## Automated checks and limits

[The GitHub Actions workflow](../.github/workflows/verify.yml) defines Java/PostgreSQL tests, frontend tests/build/dependency audit, and a Docker-backed Chromium browser job. The repository's Actions page is the source for hosted run status; the local results above do not imply a hosted run succeeded.

The SQL timings are one paired local measurement with warm-up reads, not a general throughput guarantee. Imports remain synchronous, bounded to 1 MiB/1,000 rows per source and 200 runs per workspace. There is no durable background-job system, external financial integration or multi-tenant deployment. This verification is not a full security audit or assistive-technology certification.
