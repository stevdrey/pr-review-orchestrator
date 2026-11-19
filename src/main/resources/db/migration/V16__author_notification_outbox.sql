CREATE TABLE author_notifications (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    pr_number integer NOT NULL,
    head_sha text NOT NULL CHECK (head_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    state text NOT NULL CHECK (state IN ('READY_FOR_HUMAN', 'ACTION_REQUIRED')),
    state_version bigint NOT NULL CHECK (state_version > 0),
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SKIPPED', 'ATTEMPTED', 'SENT')),
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    recipient_id bigint CHECK (recipient_id > 0),
    comment_id bigint CHECK (comment_id > 0),
    UNIQUE (installation_id, repository_id, pr_number, state_version),
    FOREIGN KEY (installation_id, repository_id, pr_number)
        REFERENCES pr_review_states (installation_id, repository_id, pr_number),
    CHECK ((recipient_id IS NOT NULL) = (status IN ('ATTEMPTED', 'SENT'))),
    CHECK ((comment_id IS NOT NULL) = (status = 'SENT'))
);
CREATE INDEX author_notifications_pending_idx ON author_notifications (available_at, id)
    WHERE status = 'PENDING' AND attempts < 3;
-- The effect is a comment on the GitHub PR, regardless of the installation that observed it.
CREATE UNIQUE INDEX author_notifications_recipient_idx
    ON author_notifications (repository_id, pr_number, head_sha, state, recipient_id)
    WHERE recipient_id IS NOT NULL;

-- Both the lifecycle and evidence ingestion update this aggregate. Capture either path on commit.
-- Existing states have no new transition and are deliberately left alone.
CREATE FUNCTION enqueue_author_notification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO author_notifications (installation_id, repository_id, pr_number, head_sha, state, state_version)
    VALUES (NEW.installation_id, NEW.repository_id, NEW.pr_number, NEW.head_sha, NEW.state, NEW.version);
    RETURN NEW;
END;
$$;

CREATE TRIGGER pr_review_states_notify_author AFTER UPDATE ON pr_review_states
    FOR EACH ROW WHEN (NEW.state IN ('READY_FOR_HUMAN', 'ACTION_REQUIRED') AND OLD.state <> NEW.state)
    EXECUTE FUNCTION enqueue_author_notification();
