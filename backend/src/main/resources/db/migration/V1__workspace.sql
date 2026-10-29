CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    name varchar(100) NOT NULL,
    email varchar(254) NOT NULL UNIQUE,
    role varchar(16) NOT NULL CHECK (role IN ('OPERATOR','REVIEWER')),
    password_hash varchar(100) NOT NULL
);
CREATE TABLE app_session (
    token_hash char(64) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id),
    csrf_token varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX session_expiry ON app_session(expires_at);
CREATE TABLE login_throttle (
    key_hash char(64) PRIMARY KEY,
    failures integer NOT NULL DEFAULT 0,
    window_start timestamptz NOT NULL,
    blocked_until timestamptz
);
CREATE TABLE workspace_guard (id integer PRIMARY KEY CHECK (id=1));
INSERT INTO workspace_guard VALUES (1);
CREATE TABLE recon_run (
    id uuid PRIMARY KEY,
    name varchar(80) NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES app_user(id),
    internal_count integer NOT NULL CHECK (internal_count BETWEEN 1 AND 1000),
    partner_count integer NOT NULL CHECK (partner_count BETWEEN 1 AND 1000)
);
CREATE TABLE import_key (
    actor_id uuid NOT NULL REFERENCES app_user(id),
    idempotency_key uuid NOT NULL,
    payload_hash char(64) NOT NULL,
    run_id uuid NOT NULL UNIQUE REFERENCES recon_run(id),
    PRIMARY KEY (actor_id,idempotency_key)
);
CREATE TABLE recon_case (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES recon_run(id),
    reference varchar(64) NOT NULL,
    classification varchar(20) NOT NULL CHECK (classification IN ('MATCHED','DIFFERENT','INTERNAL_ONLY','PARTNER_ONLY','DUPLICATE')),
    differences varchar(40) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('MATCHED','OPEN','PENDING','RESOLVED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
    internal_count integer NOT NULL,
    partner_count integer NOT NULL,
    internal_amount numeric(14,2),
    partner_amount numeric(14,2),
    delta numeric(15,2),
    UNIQUE (run_id,reference),
    CHECK ((classification='MATCHED' AND state='MATCHED') OR (classification<>'MATCHED' AND state<>'MATCHED'))
);
CREATE INDEX case_run_filter ON recon_case(run_id,state,classification,reference,id);
CREATE TABLE source_row (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES recon_case(id),
    source varchar(8) NOT NULL CHECK (source IN ('INTERNAL','PARTNER')),
    record_line integer NOT NULL CHECK (record_line>=2),
    reference varchar(64) NOT NULL,
    transaction_date date NOT NULL,
    amount numeric(14,2) NOT NULL CHECK (amount>=0),
    currency char(3) NOT NULL CHECK (currency IN ('IDR','USD')),
    UNIQUE (case_id,source,record_line)
);
CREATE TABLE proposal (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES recon_case(id),
    note varchar(2000) NOT NULL CHECK (length(note)>=10),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    decision_note varchar(2000),
    decided_by uuid REFERENCES app_user(id),
    decided_at timestamptz,
    CHECK (decided_by IS NULL OR decided_by<>created_by),
    CHECK ((status='PENDING' AND decision_note IS NULL AND decided_by IS NULL AND decided_at IS NULL)
        OR (status<>'PENDING' AND length(decision_note)>=10 AND decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE UNIQUE INDEX one_pending_proposal ON proposal(case_id) WHERE status='PENDING';
CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES recon_run(id),
    case_id uuid REFERENCES recon_case(id),
    actor_id uuid NOT NULL REFERENCES app_user(id),
    action varchar(32) NOT NULL,
    note varchar(2000) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX audit_order ON audit_event(created_at DESC,id DESC);
CREATE INDEX audit_case ON audit_event(case_id,created_at DESC,id DESC);
