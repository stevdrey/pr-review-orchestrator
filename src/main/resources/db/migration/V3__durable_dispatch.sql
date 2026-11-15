-- Fencing identities change on every claim, including recovery by the same worker process.
ALTER TABLE inbox_events ADD COLUMN lease_token uuid;
ALTER TABLE outbox_commands ADD COLUMN lease_token uuid;

-- No FK to tenant tables: legacy/poison inbox rows may name unknown installations.
-- This operator journal contains only local IDs, enums and bounded error codes, never raw errors.
CREATE TABLE queue_transitions (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    queue text NOT NULL CHECK (queue IN ('INBOX', 'OUTBOX')),
    work_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    attempt integer NOT NULL CHECK (attempt >= 0),
    outcome text NOT NULL CHECK (outcome IN ('CLAIMED', 'PROCESSED', 'SUCCEEDED', 'CANCELLED', 'RETRY', 'DEAD')),
    error_code text CHECK (error_code IN ('INTERNAL_ERROR', 'AUTHORIZATION_DENIED', 'INVALID_EVENT',
        'UNSUPPORTED_COMMAND', 'REMOTE_UNAVAILABLE', 'REMOTE_UNCERTAIN', 'LEASE_EXHAUSTED')),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX queue_transitions_correlation_idx ON queue_transitions (correlation_id, occurred_at);
CREATE INDEX queue_transitions_work_idx ON queue_transitions (queue, work_id, occurred_at);

CREATE VIEW queue_dead_letters AS
    SELECT 'INBOX'::text AS queue, id AS work_id, correlation_id, installation_id, repository_id,
        attempts, max_attempts, last_error_code, processed_at AS completed_at
    FROM inbox_events WHERE status = 'DEAD'
    UNION ALL
    SELECT 'OUTBOX', id, correlation_id, installation_id, repository_id,
        attempts, max_attempts, last_error_code, completed_at
    FROM outbox_commands WHERE status = 'DEAD';
