-- Metadata only: no tokens, raw webhook bodies, review text or private diffs.
-- These constraints support future policy/dispatch code; they do not authorize actions.
CREATE FUNCTION set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TABLE installations (
    installation_id bigint PRIMARY KEY CHECK (installation_id > 0),
    account_id bigint NOT NULL CHECK (account_id > 0),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    policy_version integer NOT NULL DEFAULT 1 CHECK (policy_version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER installations_set_updated_at BEFORE UPDATE ON installations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE installation_repositories (
    installation_id bigint NOT NULL REFERENCES installations (installation_id),
    repository_id bigint NOT NULL CHECK (repository_id > 0),
    enabled boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (installation_id, repository_id)
);

CREATE TABLE review_cycles (
    id uuid PRIMARY KEY,
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    pr_number integer NOT NULL CHECK (pr_number > 0),
    head_sha text NOT NULL CHECK (head_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    stage text NOT NULL CHECK (btrim(stage) <> ''),
    review_provider text NOT NULL CHECK (btrim(review_provider) <> ''),
    iteration integer NOT NULL DEFAULT 1 CHECK (iteration > 0),
    status text NOT NULL DEFAULT 'NEW' CHECK (status IN (
        'NEW', 'WAITING_FOR_REVIEW', 'REVIEWING', 'ACTION_REQUIRED', 'WAITING_FOR_FIX',
        'DEEP_REVIEW', 'READY_FOR_HUMAN', 'PAUSED', 'BUDGET_EXHAUSTED', 'FAILED', 'SUPERSEDED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (installation_id, repository_id)
        REFERENCES installation_repositories (installation_id, repository_id),
    UNIQUE (installation_id, repository_id, pr_number, head_sha, stage, review_provider, iteration),
    UNIQUE (id, installation_id, repository_id, head_sha)
);
CREATE TRIGGER review_cycles_set_updated_at BEFORE UPDATE ON review_cycles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE inbox_events (
    id uuid PRIMARY KEY,
    delivery_id text NOT NULL UNIQUE CHECK (btrim(delivery_id) <> ''),
    event_type text NOT NULL CHECK (btrim(event_type) <> ''),
    -- No foreign keys: signature-verified deliveries (e.g. installation.created, or events that arrive
    -- before membership sync) must be durably recorded. Workers recheck installation/repository
    -- authorization before processing or any side effect.
    installation_id bigint NOT NULL CHECK (installation_id > 0),
    -- Installation-level events need not name a repository or commit.
    repository_id bigint CHECK (repository_id > 0),
    head_sha text CHECK (head_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    payload_sha256 text NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    payload_ref text,
    correlation_id uuid NOT NULL,
    verified_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'DEAD')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts integer NOT NULL DEFAULT 5 CHECK (max_attempts > 0 AND attempts <= max_attempts),
    available_at timestamptz NOT NULL DEFAULT now(),
    locked_by text CHECK (btrim(locked_by) <> ''),
    locked_until timestamptz,
    processed_at timestamptz,
    last_error_code text,
    CHECK (status <> 'PROCESSING' OR (locked_by IS NOT NULL AND locked_until IS NOT NULL)),
    CHECK ((status IN ('PROCESSED', 'DEAD')) = (processed_at IS NOT NULL))
);
CREATE INDEX inbox_events_pending_idx ON inbox_events (available_at, received_at) WHERE status = 'PENDING';
CREATE INDEX inbox_events_lease_idx ON inbox_events (locked_until) WHERE status = 'PROCESSING';

CREATE TABLE findings (
    id uuid PRIMARY KEY,
    review_cycle_id uuid NOT NULL,
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    reviewed_sha text NOT NULL,
    source_provider text NOT NULL CHECK (btrim(source_provider) <> ''),
    source_kind text NOT NULL CHECK (source_kind IN ('REVIEW_THREAD', 'REVIEW_COMMENT', 'ISSUE_COMMENT', 'REVIEW')),
    source_id text NOT NULL CHECK (btrim(source_id) <> ''),
    -- Position of this finding within its source comment/review; part of the idempotency key.
    source_ordinal integer NOT NULL DEFAULT 0 CHECK (source_ordinal >= 0),
    thread_id text CHECK (btrim(thread_id) <> ''),
    body_ref text,
    severity text NOT NULL DEFAULT 'UNKNOWN' CHECK (severity IN ('UNKNOWN', 'INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'DISMISSED', 'VERIFIED_FIXED')),
    thread_resolved boolean,
    evidence_sha text CHECK (evidence_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    disposition_actor text CHECK (btrim(disposition_actor) <> ''),
    disposition_reason text CHECK (btrim(disposition_reason) <> ''),
    verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (review_cycle_id, installation_id, repository_id, reviewed_sha)
        REFERENCES review_cycles (id, installation_id, repository_id, head_sha),
    -- One comment/review can contain several independently tracked findings.
    UNIQUE (review_cycle_id, source_provider, source_kind, source_id, source_ordinal),
    CHECK (source_kind <> 'REVIEW_THREAD' OR thread_id IS NOT NULL),
    CHECK (source_kind NOT IN ('ISSUE_COMMENT', 'REVIEW') OR thread_id IS NULL),
    CHECK (thread_resolved IS NULL OR thread_id IS NOT NULL),
    CHECK (status NOT IN ('DISMISSED', 'VERIFIED_FIXED') OR
        (disposition_actor IS NOT NULL AND disposition_reason IS NOT NULL)),
    CHECK (status <> 'OPEN' OR (disposition_actor IS NULL AND disposition_reason IS NULL)),
    CHECK (status <> 'VERIFIED_FIXED' OR
        (evidence_sha IS NOT NULL AND verified_at IS NOT NULL AND evidence_sha <> reviewed_sha)),
    CHECK (status = 'VERIFIED_FIXED' OR (evidence_sha IS NULL AND verified_at IS NULL))
);
CREATE INDEX findings_cycle_status_idx ON findings (review_cycle_id, status);
CREATE TRIGGER findings_set_updated_at BEFORE UPDATE ON findings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE outbox_commands (
    id uuid PRIMARY KEY,
    command_key text NOT NULL UNIQUE CHECK (btrim(command_key) <> ''),
    review_cycle_id uuid NOT NULL,
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    expected_head_sha text NOT NULL,
    command_type text NOT NULL CHECK (btrim(command_type) <> ''),
    target_provider text NOT NULL CHECK (btrim(target_provider) <> ''),
    payload_ref text,
    correlation_id uuid NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'DEAD', 'CANCELLED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts integer NOT NULL DEFAULT 5 CHECK (max_attempts > 0 AND attempts <= max_attempts),
    available_at timestamptz NOT NULL DEFAULT now(),
    locked_by text CHECK (btrim(locked_by) <> ''),
    locked_until timestamptz,
    completed_at timestamptz,
    last_error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (review_cycle_id, installation_id, repository_id, expected_head_sha)
        REFERENCES review_cycles (id, installation_id, repository_id, head_sha),
    CHECK (status <> 'PROCESSING' OR (locked_by IS NOT NULL AND locked_until IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED', 'DEAD', 'CANCELLED')) = (completed_at IS NOT NULL))
);
CREATE INDEX outbox_commands_pending_idx ON outbox_commands (available_at, created_at) WHERE status = 'PENDING';
CREATE INDEX outbox_commands_lease_idx ON outbox_commands (locked_until) WHERE status = 'PROCESSING';
CREATE INDEX outbox_commands_cycle_idx
    ON outbox_commands (review_cycle_id, installation_id, repository_id, expected_head_sha);

CREATE TABLE audit_entries (
    id uuid PRIMARY KEY,
    installation_id bigint NOT NULL REFERENCES installations (installation_id),
    repository_id bigint,
    review_cycle_id uuid,
    head_sha text CHECK (head_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    correlation_id uuid NOT NULL,
    actor text NOT NULL CHECK (btrim(actor) <> ''),
    action text NOT NULL CHECK (btrim(action) <> ''),
    outcome text NOT NULL CHECK (btrim(outcome) <> ''),
    evidence_ref text,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (installation_id, repository_id)
        REFERENCES installation_repositories (installation_id, repository_id),
    CHECK (review_cycle_id IS NULL OR (repository_id IS NOT NULL AND head_sha IS NOT NULL)),
    FOREIGN KEY (review_cycle_id, installation_id, repository_id, head_sha)
        REFERENCES review_cycles (id, installation_id, repository_id, head_sha)
);
CREATE INDEX audit_entries_correlation_idx ON audit_entries (correlation_id, occurred_at);
CREATE INDEX audit_entries_scope_idx ON audit_entries (installation_id, repository_id);
CREATE INDEX audit_entries_cycle_idx ON audit_entries (review_cycle_id, installation_id, repository_id, head_sha)
    WHERE review_cycle_id IS NOT NULL;
