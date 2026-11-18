-- Confirming the same head fences older reads without recording a push.
ALTER TABLE pr_review_transitions DROP CONSTRAINT pr_review_transitions_action_check;
ALTER TABLE pr_review_transitions ADD CONSTRAINT pr_review_transitions_action_check CHECK (action IN (
    'MARK_READY', 'START_REVIEW', 'BLOCKING_FINDINGS', 'AWAIT_FIX', 'BASELINE_CLEAN',
    'DEEP_CLEAN', 'MARK_READY_FOR_HUMAN', 'PAUSE', 'RESUME', 'UPDATE_HEAD', 'CONFIRM_HEAD'));

-- Eligibility is durable and deduplicated, but grants no authority to invoke a provider.
CREATE TABLE fix_review_candidates (
    id uuid PRIMARY KEY,
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    pr_number integer NOT NULL,
    head_sha text NOT NULL CHECK (head_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    base_sha text NOT NULL CHECK (base_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$' AND base_sha <> head_sha),
    head_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    -- head_version fixes the base: a later fix transition at the same head yields no candidate.
    UNIQUE (installation_id, repository_id, pr_number, head_sha, head_version),
    FOREIGN KEY (installation_id, repository_id, pr_number, head_version)
        REFERENCES pr_review_transitions (installation_id, repository_id, pr_number, resulting_version)
);
CREATE INDEX fix_review_candidates_transition_idx
    ON fix_review_candidates (installation_id, repository_id, pr_number, head_version);
