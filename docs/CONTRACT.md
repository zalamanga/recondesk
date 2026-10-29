# ReconDesk implementation contract

This file is the shared interface for the initial implementation. Update it when an implemented decision changes.

## Scope and direction

Local training application using synthetic transactions. Compare two CSV sources by reference, investigate exceptions, propose an explanation, and require a different reviewer to approve or reject it. Approval records an investigation decision; it never edits source transactions or moves money.

User direction: light, orderly work application. Reading this as an operations workspace for transaction investigators and reviewers, with a light paper-and-ledger visual language, ENERGY 1 / RHYTHM 2 / MOTION 1. No invented business metrics, testimonials, logos, or product claims. Actual run totals supply the interface. Root owns the final antislop gate.

## Stack

Java 17, Spring Boot (current stable 4.1 line, exact version pinned in pom.xml), Spring JDBC, PostgreSQL 17, Flyway, Apache Commons CSV 1.14.1. React 19 + TypeScript + Vite, CSS written for this application. Same-origin API behind Nginx. Native tests and isolated PostgreSQL tests; Docker Compose for delivery. No external payment/email/provider integration.

## HTTP interface

All business routes require a session. UUIDs are strings. ISO timestamps are UTC. Money is always a decimal STRING with exactly two fractional digits. Dates are ISO YYYY-MM-DD. JSON errors: `{code, message}` with optional structured details. Use 400 validation, 401 session/login, 403 role/origin/CSRF, 404 missing record, 409 version/idempotency conflict, 413 upload limit, 429 login throttling.

Session cookie: `recondesk_session`, HttpOnly, SameSite=Lax, scoped to `/`, 12-hour expiry. Hash opaque tokens in DB. Require exact configured Origin plus `X-CSRF-Token` on authenticated mutations; login requires same origin and JSON. Tokens must not be logged. Both users can read all workspace runs, cases and audit. OPERATOR can import and propose, REVIEWER can decide. Enforce role and a different actor on the server. No registration or first-request DDL.

- `GET /api/health`: anonymous readiness, DB SELECT, `{status:"ok"}`.
- `POST /api/auth/login`: `{email,password}` -> `{user,csrfToken}`.
- `GET /api/auth/session`: `{user,csrfToken}`, or 401.
- `POST /api/auth/logout`: CSRF-protected, revoke current session, 204.
- `GET /api/runs?page=0`: page response of Run.
- `POST /api/runs`: multipart `name`, `internalFile`, `partnerFile`; UUID `Idempotency-Key` header. Return Run (201, or 200 committed replay). Key scoped to actor; same key and different payload -> 409. Validate both sources completely before writing. Transaction creates both source datasets, results and audit atomically. Do not persist uploaded files to disk.
- `GET /api/runs/{id}`: Run.
- `GET /api/runs/{id}/cases?page=0&state=ALL&classification=ALL&search=`: page response of CaseSummary; use bound SQL and bounded search. Page size 25 fixed, zero-based. Valid filters below.
- `GET /api/cases/{id}`: CaseDetail.
- `POST /api/cases/{id}/proposals`: `{expectedVersion,note}` -> CaseDetail. OPERATOR, OPEN only, note 10..2000 characters. Rejection reopens a case; operator can propose again. Matched/resolved/pending cases reject mutation.
- `POST /api/proposals/{id}/decision`: `{expectedVersion,decision:"APPROVE"|"REJECT",note}` -> CaseDetail. REVIEWER, pending proposal only, different from proposer. Note 10..2000 characters for either decision. Lock case, compare version, update case/proposal and append audit in one transaction.
- `GET /api/runs/{id}/export.csv`: CSV of complete run results including investigation status. Protect spreadsheet formulas. No silent truncation (bounded import size).
- `GET /api/audit?page=0`: page response of AuditItem, newest first.

Page response: `{items:[],total:0,page:0,pageSize:25}`. Stable deterministic order.

User: `{id,name,email,role:"OPERATOR"|"REVIEWER"}`.

Run: `{id,name,createdAt,createdBy:User,internalCount,partnerCount,caseCount,matchedCount,openCount,pendingCount,resolvedCount}`. Counts reflect current workflow states; open + pending + resolved + matched = caseCount.

CaseSummary: `{id,runId,reference,classification,differences:string[],state,version,internalCount,partnerCount,internalAmount:string|null,partnerAmount:string|null,delta:string|null}`. Classification is `MATCHED`, `DIFFERENT`, `INTERNAL_ONLY`, `PARTNER_ONLY`, or `DUPLICATE`. Differences use `AMOUNT`, `DATE`, `CURRENCY`. State is `MATCHED`, `OPEN`, `PENDING`, or `RESOLVED`. For duplicates amount/delta are null, not a misleading arbitrary selected row. Delta means internal minus partner for the same currency. A missing side is compared against zero; two different currencies always have null delta because there is no FX conversion.

CaseDetail extends CaseSummary: `{runName,internalRows:SourceRow[],partnerRows:SourceRow[],proposals:Proposal[],audit:AuditItem[]}`.

SourceRow: `{line,reference,date,amount,currency}`. Preserve every validated source row, including duplicates. Line is the CSV record number with header as record 1.

Proposal: `{id,caseId,note,createdBy:User,createdAt,status:"PENDING"|"APPROVED"|"REJECTED",decisionNote:string|null,decidedBy:User|null,decidedAt:string|null}`.

AuditItem: `{id,runId,caseId:string|null,actor:User,action,note,createdAt}`. Application API has no audit editing/deletion endpoint; do not claim tamper-proof storage.

## CSV rules

Exact header `reference,date,amount,currency`. UTF-8 (optional BOM), RFC-style quoting, valid CSV structure, no extra/missing columns or headers, no blank data records. Reject malformed encoding, negative amounts, exponent notation, more than 2 decimal places, >999999999999.99, invalid dates or years outside 0001..9999, unsupported currencies. Normalize validated amounts to scale 2. Reference regex `[A-Za-z0-9._/-]{1,64}`, trim outside whitespace; currency `IDR` or `USD`. Maximum 1 MiB and 1000 data rows per source; both files must contain >=1 row. Run name 3..80 characters. Cap workspace at 200 runs using a transactional guard.

Group by reference only. If either source has >1 row for a reference, classify DUPLICATE and retain all rows. Otherwise, only one side -> INTERNAL_ONLY/PARTNER_ONLY. One row on both sides with all amount/date/currency equal -> MATCHED; otherwise DIFFERENT with exact mismatch fields. Do not match solely by monetary value or silently pick duplicates.

## Concurrency and credentials

Expected versions are nonnegative integers. SELECT FOR UPDATE protects proposal/decision transitions, with DB constraints as backup. Repeated decision must not append another audit event. Different stale payload -> 409; no overwrite. Missing version -> 400. Two parallel imports with same key must yield one run. Store import payload hash including name and both original byte streams with unambiguous boundaries.

Seed only two synthetic users on an empty account table using required environment secret `RECON_DEMO_PASSWORD` (generated locally by scripts). Emails `operator@recondesk.test`, `reviewer@recondesk.test`; names Demo Operator, Demo Reviewer. Password hashing. Do not reset existing accounts/data on startup. Local password in ignored `.local/LOCAL-CREDENTIALS.txt`, never a public default.

## Frontend workflow

English product copy; Indonesian tutorial docs. Logged-out login form (password not prefilled). Workspace has Runs, Import CSV, and Audit navigation; run selection shows counts and filtered exception list. Detail opens a keyboard-usable panel/page with complete original rows, proposal history, decision form by role and real read-only reasons. Handle loading, empty, failure, conflict, expired session and forbidden role without fake success. Import explains limits, links downloadable synthetic samples, keeps idempotency identity stable for retry of uncertain response, and never automatically resubmits changed input. Disable pending mutations; confirmation is the actual completed server response. Responsive at 390px with usable reflow and 44px controls; scoped table scroll if truly tabular. Source comparison is the repeated identity motif.

## Ownership during implementation

Backend agent: `backend/` only. Frontend agent: `frontend/` only. Documentation agent: sample CSVs plus documentation except this shared contract/root QA reports. Root: Docker/PowerShell/CI/Git/overall integration, screenshots, benchmark artifacts, final visual gate, workspace list and publication. Do not overwrite another owner's files; communicate contract changes first.
