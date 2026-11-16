-- One authoritative local aggregate per PR, separate from provider/stage review_cycles.
-- Existing cycles cannot establish a current remote head; do not backfill inferred approvals.
CREATE TABLE pr_review_states (
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    pr_number integer NOT NULL CHECK (pr_number > 0),
    head_sha text NOT NULL CHECK (head_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    state text NOT NULL DEFAULT 'NEW' CHECK (state IN (
        'NEW', 'WAITING_FOR_REVIEW', 'REVIEWING', 'ACTION_REQUIRED', 'WAITING_FOR_FIX',
        'DEEP_REVIEW', 'PAUSED', 'READY_FOR_HUMAN')),
    resume_state text CHECK (resume_state IN (
        'NEW', 'WAITING_FOR_REVIEW', 'REVIEWING', 'ACTION_REQUIRED', 'WAITING_FOR_FIX',
        'DEEP_REVIEW', 'READY_FOR_HUMAN')),
    baseline_clean_sha text,
    deep_clean_sha text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (installation_id, repository_id, pr_number),
    FOREIGN KEY (installation_id, repository_id)
        REFERENCES installation_repositories (installation_id, repository_id),
    CHECK ((state = 'PAUSED') = (resume_state IS NOT NULL)),
    CHECK (baseline_clean_sha IS NULL OR baseline_clean_sha = head_sha),
    CHECK (deep_clean_sha IS NULL OR (deep_clean_sha = head_sha AND baseline_clean_sha IS NOT NULL)),
    CHECK ((coalesce(resume_state, state) IN ('DEEP_REVIEW', 'READY_FOR_HUMAN')) =
        (baseline_clean_sha IS NOT NULL)),
    CHECK (coalesce(resume_state, state) <> 'READY_FOR_HUMAN' OR deep_clean_sha IS NOT NULL)
);
CREATE TRIGGER pr_review_states_set_updated_at BEFORE UPDATE ON pr_review_states
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- A replay is explicitly rejected, even if the aggregate has advanced since the original command.
CREATE TABLE pr_review_transitions (
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    pr_number integer NOT NULL,
    transition_id uuid NOT NULL,
    expected_version bigint NOT NULL CHECK (expected_version >= 0),
    expected_head_sha text NOT NULL CHECK (expected_head_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    action text NOT NULL CHECK (action IN (
        'MARK_READY', 'START_REVIEW', 'BLOCKING_FINDINGS', 'AWAIT_FIX', 'BASELINE_CLEAN',
        'DEEP_CLEAN', 'MARK_READY_FOR_HUMAN', 'PAUSE', 'RESUME', 'UPDATE_HEAD')),
    resulting_version bigint NOT NULL CHECK (resulting_version = expected_version + 1),
    resulting_head_sha text NOT NULL CHECK (resulting_head_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    resulting_state text NOT NULL CHECK (resulting_state IN (
        'NEW', 'WAITING_FOR_REVIEW', 'REVIEWING', 'ACTION_REQUIRED', 'WAITING_FOR_FIX',
        'DEEP_REVIEW', 'PAUSED', 'READY_FOR_HUMAN')),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (installation_id, repository_id, pr_number, transition_id),
    UNIQUE (installation_id, repository_id, pr_number, resulting_version),
    FOREIGN KEY (installation_id, repository_id, pr_number)
        REFERENCES pr_review_states (installation_id, repository_id, pr_number)
);
