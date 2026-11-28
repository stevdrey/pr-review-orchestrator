-- Created and revoked by a trusted operator. The HTTP service only reads these grants.
CREATE TABLE mcp_tool_sessions (
    token_sha256 text PRIMARY KEY CHECK (token_sha256 ~ '^[0-9a-f]{64}$'),
    actor_id bigint NOT NULL CHECK (actor_id > 0),
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    pr_number integer NOT NULL CHECK (pr_number > 0),
    issued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    approved_tool text CHECK (approved_tool IN ('pause_cycle', 'request_review')),
    command_id uuid UNIQUE,
    approved_head_sha text CHECK (approved_head_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    approved_version bigint CHECK (approved_version BETWEEN 0 AND 9223372036854775806),
    FOREIGN KEY (installation_id, repository_id, pr_number)
        REFERENCES pr_review_states (installation_id, repository_id, pr_number),
    CHECK (expires_at > issued_at AND expires_at <= issued_at + interval '15 minutes'),
    CHECK (num_nonnulls(approved_tool, command_id, approved_head_sha, approved_version) IN (0, 4))
);
