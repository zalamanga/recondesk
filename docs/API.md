# HTTP API

The browser uses the same origin as the application, normally `http://127.0.0.1:8096`. Use that exact address: `localhost` is a different origin. Paths below are relative to that origin. Only exact `GET /api/health` and `POST /api/auth/login` are public backend exceptions. Every other backend path requires a valid session, including unrecognized or noncanonical paths; an anonymous request can therefore receive `401` before route resolution.

## Session

| Method and route | Request | Successful response |
| --- | --- | --- |
| `GET /api/health` | None | `200 {"status":"ok"}` after a database check |
| `POST /api/auth/login` | JSON `{email,password}` | `200 {user,csrfToken}` and session cookie |
| `GET /api/auth/session` | Session cookie | `200 {user,csrfToken}` |
| `POST /api/auth/logout` | Session cookie, origin, CSRF header | `204`, current session revoked |

Cookie `recondesk_session` is HttpOnly, SameSite=Lax, path `/`, with a 12-hour expiry. The database stores a hash of the opaque session token. Send credentials with browser requests. JavaScript uses the returned CSRF token, not the cookie value.

Login requires JSON and the exact configured `Origin`. Every authenticated mutation requires that origin and `X-CSRF-Token`. Never put tokens or passwords in a URL or commit them to the repository. A missing or expired session returns `401`.

`User` is `{id,name,email,role}`. Roles are `OPERATOR` and `REVIEWER`. Both can read all runs, cases, exports, and audit events in the local workspace.

## Runs

| Method and route | Role | Behavior |
| --- | --- | --- |
| `GET /api/runs?page=0` | Either | Paged runs |
| `POST /api/runs` | Operator | Import two validated sources atomically |
| `GET /api/runs/{id}` | Either | One run and current counts |
| `GET /api/runs/{id}/cases` | Either | Paged, filtered cases |
| `GET /api/runs/{id}/export.csv` | Either | Entire run as CSV, with investigation state |

Import uses `multipart/form-data` fields `name`, `internalFile`, and `partnerFile`, plus a UUID `Idempotency-Key` header. Run names have 3 to 80 characters. A new import returns `201`; a replay of an identical committed request returns `200`. Reusing the actor's key with a different name or file bytes returns `409`. Both complete sources must validate before the service writes anything. Workspace capacity is 200 runs.

Case query parameters are `page`, `state`, `classification`, and `search`. Page numbers are zero-based, accepted from 0 to 100000, and page size is fixed at 25. State accepts `ALL`, `MATCHED`, `OPEN`, `PENDING`, or `RESOLVED`. Classification accepts `ALL`, `MATCHED`, `DIFFERENT`, `INTERNAL_ONLY`, `PARTNER_ONLY`, or `DUPLICATE`. Search has at most 64 characters and is passed to SQL as a parameter with literal wildcard escaping.

Every page has this shape:

```json
{"items":[],"total":0,"page":0,"pageSize":25}
```

`Run` contains:

```text
id, name, createdAt, createdBy: User,
internalCount, partnerCount, caseCount,
matchedCount, openCount, pendingCount, resolvedCount
```

The four state counts sum to `caseCount`. Counts change after investigation decisions, while the original source counts remain fixed.

## Cases and investigation

| Method and route | Role | Request | Response |
| --- | --- | --- | --- |
| `GET /api/cases/{id}` | Either | None | `CaseDetail` |
| `POST /api/cases/{id}/proposals` | Operator | `{expectedVersion,note}` | Updated `CaseDetail` |
| `POST /api/proposals/{id}/decision` | Reviewer | `{expectedVersion,decision,note}` | Updated `CaseDetail` |

Both mutation notes require 10 to 2,000 characters. `expectedVersion` is a required nonnegative integer. `decision` is `APPROVE` or `REJECT`.

An operator can propose only on `OPEN` exceptions. A reviewer can decide only a pending proposal and must differ from its proposer. Approval resolves the case; rejection reopens it so an operator may submit another proposal. A matched case cannot enter this workflow. A stale version, invalid workflow transition, or conflicting mutation returns `409` instead of overwriting data. A repeated decision does not append another decision event.

`CaseSummary` contains:

```text
id, runId, reference, classification, differences: string[], state, version,
internalCount, partnerCount, internalAmount, partnerAmount, delta
```

`classification` is `MATCHED`, `DIFFERENT`, `INTERNAL_ONLY`, `PARTNER_ONLY`, or `DUPLICATE`. `differences` contains any of `AMOUNT`, `DATE`, and `CURRENCY`. `state` is `MATCHED`, `OPEN`, `PENDING`, or `RESOLVED`.

`CaseDetail` adds `runName`, `internalRows`, `partnerRows`, `proposals`, and `audit`. A source row is `{line,reference,date,amount,currency}`. `line` is the CSV record number, counting the header as record 1. Multiline quoted records are records, so this number is not necessarily a physical text-line number.

`Proposal` contains:

```text
id, caseId, note, createdBy: User, createdAt,
status, decisionNote, decidedBy: User|null, decidedAt
```

Status is `PENDING`, `APPROVED`, or `REJECTED`. Decision fields are null until a decision exists. Proposal history remains visible after rejection and resubmission.

UUIDs are strings, timestamps are UTC ISO strings, and dates use `YYYY-MM-DD`. Money uses decimal strings with exactly two fractional digits, such as `"42.50"`. Duplicate groups have null summary amounts and delta. A unique pair with different currencies also has null delta because the application does not convert currencies. Otherwise delta is internal minus partner, using zero for a missing side; the classification still identifies that missing source. The export protects cells that could be interpreted as spreadsheet formulas and includes the complete bounded run.

## Audit

`GET /api/audit?page=0` returns a page of `AuditItem`, newest first with stable tie-breaking. Each item has `{id,runId,caseId,actor,action,note,createdAt}`; `caseId` may be null for run-level actions. The application exposes no audit update or delete endpoint. This is not a claim that a database administrator cannot change stored data.

## CSV validation

The exact header is:

```csv
reference,date,amount,currency
```

- UTF-8, optionally with a BOM; valid RFC-style CSV quoting.
- Exactly four columns, no missing or extra headers, and no blank data records.
- Between 1 and 1,000 data rows per file; maximum 1 MiB per source.
- Reference: trim outside whitespace, then match `[A-Za-z0-9._/-]{1,64}`.
- Date: a real ISO calendar date, `YYYY-MM-DD`, with year 0001 to 9999.
- Amount: nonnegative decimal, no exponent notation, at most two fractional digits, at most `999999999999.99`. The server normalizes accepted amounts to two digits.
- Currency: `IDR` or `USD`.

Either file failing validation rejects the whole import. Files in [samples/invalid](../samples/invalid) deliberately violate individual rules. [samples/README.md](../samples/README.md) records the classifications expected from the valid pair.

## Errors

Errors have `{code,message}` and may include structured validation details. Treat `code` and the HTTP status as machine-readable; display `message` as text.

| Status | Meaning |
| --- | --- |
| `400` | Invalid input, filter, version, or CSV content |
| `401` | Invalid login or missing/expired session |
| `403` | Role, origin, or CSRF check failed |
| `404` | Run, case, or proposal does not exist |
| `409` | Stale version, workflow conflict, idempotency mismatch, or workspace capacity conflict |
| `413` | Upload exceeds the accepted size |
| `429` | Login temporarily throttled |
| `500` | Unexpected server failure with a generic error message |

On `409`, reload the case before submitting a new decision. After an uncertain import response, retry the unchanged request with the same idempotency key; do not generate a new key merely because the response was lost. On `401`, return to login and recover the current view after authentication.

## Server configuration names

The backend reads these environment variables. The supplied Compose file supplies its database connection and the initialization script generates private passwords.

| Variable | Purpose |
| --- | --- |
| `PORT` | Backend listener, default `8080` |
| `RECON_DB_URL` | PostgreSQL JDBC URL |
| `RECON_DB_USER` | Database user |
| `RECON_DB_PASSWORD` | Database password |
| `RECON_APP_ORIGIN` | Exact allowed browser origin, default `http://127.0.0.1:8096` |
| `RECON_COOKIE_SECURE` | Secure cookie flag; local HTTP defaults to `false` |
| `RECON_DEMO_PASSWORD` | Required generated seed password when the account table is empty |

Integration tests instead select a dedicated database with `RECON_TEST_DATABASE_URL`, `RECON_TEST_DB_USER`, and `RECON_TEST_DB_PASSWORD`. Browser tests use `RECON_BASE_URL`, `RECON_DEMO_PASSWORD`, and optionally `RECON_BROWSER_CHANNEL`. See [operations](OPERATIONS.md) before running tests against a persistent workspace.
