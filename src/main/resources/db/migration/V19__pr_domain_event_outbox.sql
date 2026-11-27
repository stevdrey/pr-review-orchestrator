CREATE TABLE pr_event_subscriptions (
    id uuid PRIMARY KEY,
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    pr_number integer CHECK (pr_number > 0),
    callback_sha256 text NOT NULL CHECK (callback_sha256 ~ '^[0-9a-f]{64}$'),
    revoked_at timestamptz,
    FOREIGN KEY (installation_id, repository_id)
        REFERENCES installation_repositories (installation_id, repository_id)
);
CREATE INDEX pr_event_subscriptions_active_idx
    ON pr_event_subscriptions (installation_id, repository_id, pr_number) WHERE revoked_at IS NULL;

-- A new recipient or filter requires a new ID. A revoked ID is never reactivated.
CREATE FUNCTION preserve_pr_event_subscription() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.installation_id, NEW.repository_id, NEW.pr_number, NEW.callback_sha256)
        IS DISTINCT FROM (OLD.id, OLD.installation_id, OLD.repository_id, OLD.pr_number, OLD.callback_sha256)
        OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Domain event subscription binding is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER pr_event_subscriptions_preserve BEFORE UPDATE ON pr_event_subscriptions
    FOR EACH ROW EXECUTE FUNCTION preserve_pr_event_subscription();

-- The journal is immutable application evidence. Reference it instead of copying a JSON payload.
CREATE TABLE pr_domain_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    pr_number integer NOT NULL,
    transition_id uuid NOT NULL,
    UNIQUE (installation_id, repository_id, pr_number, transition_id),
    FOREIGN KEY (installation_id, repository_id, pr_number, transition_id)
        REFERENCES pr_review_transitions (installation_id, repository_id, pr_number, transition_id)
);

CREATE TABLE pr_event_deliveries (
    subscription_id uuid NOT NULL REFERENCES pr_event_subscriptions (id),
    event_id uuid NOT NULL REFERENCES pr_domain_events (id),
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DELIVERED', 'CANCELLED', 'DEAD')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_outcome text CHECK (last_outcome IN ('IN_FLIGHT', 'ACKNOWLEDGED', 'RETRY', 'REJECTED',
        'REVOKED', 'AUTHORIZATION_DENIED', 'AUTHORIZATION_UNAVAILABLE', 'LEASE_EXPIRED')),
    PRIMARY KEY (subscription_id, event_id)
);
CREATE INDEX pr_event_deliveries_pending_idx ON pr_event_deliveries (available_at, event_id, subscription_id)
    WHERE status = 'PENDING';

-- Both lifecycle commands and readiness invalidation insert journal rows. Their event and
-- matching deliveries commit or roll back with the transition. Existing history is not replayed.
CREATE FUNCTION enqueue_pr_domain_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    -- Allocate the ID before INSERT so the journal writer can use insert-only outbox grants.
    new_event_id uuid := gen_random_uuid();
BEGIN
    INSERT INTO pr_domain_events (id, installation_id, repository_id, pr_number, transition_id)
    VALUES (new_event_id, NEW.installation_id, NEW.repository_id, NEW.pr_number, NEW.transition_id);

    INSERT INTO pr_event_deliveries (subscription_id, event_id)
    SELECT id, new_event_id FROM pr_event_subscriptions
    WHERE installation_id = NEW.installation_id AND repository_id = NEW.repository_id
        AND (pr_number IS NULL OR pr_number = NEW.pr_number) AND revoked_at IS NULL;
    RETURN NEW;
END;
$$;

CREATE TRIGGER pr_review_transitions_publish AFTER INSERT ON pr_review_transitions
    FOR EACH ROW EXECUTE FUNCTION enqueue_pr_domain_event();
