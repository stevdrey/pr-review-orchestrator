-- Only the sandbox planner supplies an observed head epoch. Earlier or generic commands
-- have no trusted dispatch-time visit and are not eligible for the Codex outbox worker.
ALTER TABLE outbox_commands ADD COLUMN planned_head_epoch bigint CHECK (planned_head_epoch >= 0);
ALTER TABLE review_requests ADD COLUMN planned_command_id uuid UNIQUE REFERENCES outbox_commands (id);

-- Generic review providers retain the existing evidence contract. Codex attempt metadata is all or none.
ALTER TABLE review_clean_evidence
    ADD COLUMN trigger_comment_id bigint CHECK (trigger_comment_id > 0),
    ADD COLUMN provider_execution_id text CHECK (provider_execution_id ~ '^[A-Za-z0-9_:/.-]{1,255}$'),
    ADD COLUMN provider_attempt_id text CHECK (provider_attempt_id ~ '^[A-Za-z0-9_:/.-]{1,255}$'),
    ADD COLUMN provider_terminal_at timestamptz,
    ADD CONSTRAINT review_clean_codex_provenance CHECK (
        num_nonnulls(trigger_comment_id, provider_execution_id, provider_attempt_id, provider_terminal_at) = 0
        OR (provider = 'codex'
            AND num_nonnulls(trigger_comment_id, provider_execution_id, provider_attempt_id, provider_terminal_at) = 4)
    );
CREATE UNIQUE INDEX review_clean_codex_attempt_key
    ON review_clean_evidence (provider_execution_id, provider_attempt_id)
    WHERE provider_execution_id IS NOT NULL;
