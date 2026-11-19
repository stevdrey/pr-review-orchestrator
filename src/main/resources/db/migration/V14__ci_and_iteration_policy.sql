ALTER TABLE pr_review_states
    ADD COLUMN ci_status text NOT NULL DEFAULT 'UNKNOWN' CHECK (ci_status IN ('UNKNOWN', 'PENDING', 'FAILED', 'PASSED')),
    ADD COLUMN intervention text CHECK (intervention IS NULL
        OR (state = 'PAUSED' AND intervention IN ('ITERATION_LIMIT', 'NO_PROGRESS')));

ALTER TABLE pr_review_transitions DROP CONSTRAINT pr_review_transitions_action_check;
ALTER TABLE pr_review_transitions
    ADD CONSTRAINT pr_review_transitions_action_check CHECK (action IN (
        'MARK_READY', 'START_REVIEW', 'BLOCKING_FINDINGS', 'AWAIT_FIX', 'BASELINE_CLEAN',
        'DEEP_CLEAN', 'MARK_READY_FOR_HUMAN', 'PAUSE', 'RESUME', 'UPDATE_HEAD', 'CONFIRM_HEAD',
        'RECONCILE_CI', 'STOP_ITERATION_LIMIT', 'STOP_NO_PROGRESS')),
    ADD COLUMN ci_status text CHECK (ci_status IN ('UNKNOWN', 'PENDING', 'FAILED', 'PASSED')),
    ADD CHECK ((action = 'RECONCILE_CI') = (ci_status IS NOT NULL));

-- Legacy readiness has no durable CI evidence. Keep both clean verdicts and the existing audit history.
WITH revoked AS (
    UPDATE pr_review_states SET version = version + 1,
        state = CASE WHEN state = 'READY_FOR_HUMAN' THEN 'DEEP_REVIEW' ELSE state END,
        resume_state = CASE WHEN resume_state = 'READY_FOR_HUMAN' THEN 'DEEP_REVIEW' ELSE resume_state END
    WHERE state = 'READY_FOR_HUMAN' OR resume_state = 'READY_FOR_HUMAN'
    RETURNING installation_id, repository_id, pr_number, head_sha, version, state
)
INSERT INTO pr_review_transitions (installation_id, repository_id, pr_number, transition_id,
    expected_version, expected_head_sha, action, resulting_version, resulting_head_sha, resulting_state, ci_status)
SELECT installation_id, repository_id, pr_number, gen_random_uuid(), version - 1, head_sha, 'RECONCILE_CI',
    version, head_sha, state, 'UNKNOWN' FROM revoked;

ALTER TABLE pr_review_states ADD CHECK (
    coalesce(resume_state, state) <> 'READY_FOR_HUMAN' OR ci_status = 'PASSED');

ALTER TABLE review_requests ADD COLUMN iteration bigint;
WITH ordered AS (
    SELECT id, row_number() OVER (PARTITION BY installation_id, repository_id, pr_number ORDER BY created_at, id) AS slot
    FROM review_requests
)
UPDATE review_requests r SET iteration = o.slot FROM ordered o WHERE r.id = o.id;
ALTER TABLE review_requests
    ALTER COLUMN iteration SET NOT NULL,
    ADD CHECK (iteration > 0),
    ADD UNIQUE (installation_id, repository_id, pr_number, iteration);

-- The latest fingerprint per request and whether that request caused a no-progress pause (set at most once).
-- Historical requests keep their slots.
CREATE TABLE review_round_progress (
    request_id uuid PRIMARY KEY REFERENCES review_requests (id),
    snapshot_id uuid NOT NULL,
    review_source_id text NOT NULL,
    finding_sha256 text NOT NULL CHECK (finding_sha256 ~ '^[0-9a-f]{64}$'),
    no_progress boolean NOT NULL DEFAULT false,
    FOREIGN KEY (snapshot_id, review_source_id) REFERENCES review_evidence_sources (snapshot_id, source_id)
);
