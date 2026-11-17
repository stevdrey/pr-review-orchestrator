-- Permanent metadata-only request tombstones, independent of event/cycle/actor identity.
CREATE TABLE review_requests (
    id uuid PRIMARY KEY,
    request_key text NOT NULL UNIQUE,
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    pr_number integer NOT NULL CHECK (pr_number > 0),
    head_sha text NOT NULL CHECK (head_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    stage text NOT NULL CHECK (stage ~ '^[A-Z_]{1,32}$'),
    provider text NOT NULL CHECK (provider ~ '^[a-z0-9_-]{1,32}$'),
    actor_id bigint NOT NULL CHECK (actor_id > 0),
    state text NOT NULL DEFAULT 'RESERVED' CHECK (state IN ('RESERVED', 'ATTEMPTED', 'COMMENT_CREATED')),
    comment_id bigint CHECK (comment_id > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    FOREIGN KEY (installation_id, repository_id) REFERENCES installation_repositories (installation_id, repository_id),
    UNIQUE (installation_id, repository_id, pr_number, head_sha, stage, provider),
    CHECK (request_key = installation_id || '/' || repository_id || '/' || pr_number || '/' || head_sha || '/' || stage || '/' || provider),
    CHECK ((state = 'COMMENT_CREATED') = (comment_id IS NOT NULL AND completed_at IS NOT NULL)),
    CHECK (state = 'COMMENT_CREATED' OR (comment_id IS NULL AND completed_at IS NULL))
);
CREATE INDEX review_requests_pr_idx ON review_requests (installation_id, repository_id, pr_number);
