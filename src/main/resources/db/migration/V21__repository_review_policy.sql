CREATE TABLE repository_review_policy_versions (
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    stages text[] NOT NULL CHECK (stages <@ ARRAY['BASELINE', 'DEEP']::text[] AND array_position(stages, NULL) IS NULL),
    providers text[] NOT NULL CHECK (cardinality(providers) <= 16 AND array_position(providers, NULL) IS NULL
        AND array_to_string(providers, ',') ~ '^([a-z0-9_-]{1,32}(,[a-z0-9_-]{1,32})*)?$'),
    require_passing_ci boolean NOT NULL,
    quiet_zone text,
    quiet_start_minute integer CHECK (quiet_start_minute BETWEEN 0 AND 1439),
    quiet_end_minute integer CHECK (quiet_end_minute BETWEEN 0 AND 1439),
    iteration_cap integer NOT NULL CHECK (iteration_cap BETWEEN 0 AND 6),
    held boolean NOT NULL,
    changed_by bigint NOT NULL CHECK (changed_by > 0),
    change_kind text NOT NULL CHECK (change_kind IN ('CONFIGURE', 'PAUSE', 'RESUME')),
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (installation_id, repository_id, version),
    FOREIGN KEY (installation_id, repository_id) REFERENCES installation_repositories (installation_id, repository_id),
    CHECK (num_nonnulls(quiet_zone, quiet_start_minute, quiet_end_minute) IN (0, 3)),
    CHECK (quiet_start_minute <> quiet_end_minute)
);

CREATE FUNCTION reject_repository_review_policy_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Repository review policy versions are append-only';
END;
$$;
CREATE TRIGGER repository_review_policy_append_only BEFORE UPDATE OR DELETE ON repository_review_policy_versions
    FOR EACH ROW EXECUTE FUNCTION reject_repository_review_policy_mutation();

-- Provisioned by a trusted database administrator. The CLI authenticates as this named login.
CREATE TABLE repository_review_policy_operators (
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    database_role name NOT NULL,
    actor_id bigint NOT NULL CHECK (actor_id > 0),
    PRIMARY KEY (installation_id, repository_id, database_role),
    FOREIGN KEY (installation_id, repository_id) REFERENCES installation_repositories (installation_id, repository_id)
);

-- Qualify application tables with the trigger's schema, independent of the operator's search path.
CREATE FUNCTION authorize_repository_review_policy_insert() RETURNS trigger LANGUAGE plpgsql
    SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    authorized integer;
    previous record;
BEGIN
    EXECUTE format(
        'SELECT 1 FROM %1$I.repository_review_policy_operators o
         JOIN %1$I.installation_repositories r USING (installation_id, repository_id)
         JOIN %1$I.installations i USING (installation_id)
         WHERE (o.installation_id, o.repository_id, o.actor_id, o.database_role) = ($1, $2, $3, $4)
           AND i.status = ''ACTIVE'' AND r.enabled
         FOR SHARE OF i, r, o', TG_TABLE_SCHEMA)
        INTO authorized USING NEW.installation_id, NEW.repository_id, NEW.changed_by, session_user;
    IF authorized IS NULL THEN
        RAISE EXCEPTION 'Repository policy operator denied' USING ERRCODE = '42501';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        'review-policy:' || NEW.installation_id || ':' || NEW.repository_id, 0));
    EXECUTE format(
        'SELECT version, stages, providers, require_passing_ci, quiet_zone, quiet_start_minute,
                quiet_end_minute, iteration_cap, held
         FROM %I.repository_review_policy_versions WHERE (installation_id, repository_id) = ($1, $2)
         ORDER BY version DESC LIMIT 1', TG_TABLE_SCHEMA)
        INTO previous USING NEW.installation_id, NEW.repository_id;

    IF NEW.version <> coalesce(previous.version, 0) + 1
        OR (NEW.change_kind = 'CONFIGURE' AND NEW.held <> coalesce(previous.held, false))
        OR (NEW.change_kind <> 'CONFIGURE' AND (
            NEW.held <> (NEW.change_kind = 'PAUSE')
            OR (NEW.stages, NEW.providers, NEW.require_passing_ci, NEW.quiet_zone, NEW.quiet_start_minute,
                    NEW.quiet_end_minute, NEW.iteration_cap)
                IS DISTINCT FROM (coalesce(previous.stages, ARRAY[]::text[]), coalesce(previous.providers, ARRAY[]::text[]),
                    coalesce(previous.require_passing_ci, true), previous.quiet_zone, previous.quiet_start_minute,
                    previous.quiet_end_minute, coalesce(previous.iteration_cap, 0)))) THEN
        RAISE EXCEPTION 'Invalid repository policy version or operation' USING ERRCODE = '23514';
    END IF;
    NEW.changed_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER repository_review_policy_authorized_insert BEFORE INSERT ON repository_review_policy_versions
    FOR EACH ROW EXECUTE FUNCTION authorize_repository_review_policy_insert();
REVOKE ALL ON FUNCTION authorize_repository_review_policy_insert() FROM PUBLIC;

-- Historical work has no reliable policy provenance. Keep receipts and keys without synthesizing one.
ALTER TABLE outbox_commands ADD COLUMN repository_policy_version bigint,
    ADD FOREIGN KEY (installation_id, repository_id, repository_policy_version)
        REFERENCES repository_review_policy_versions (installation_id, repository_id, version),
    ADD CHECK (repository_policy_version IS NULL OR command_type = 'REQUEST_REVIEW');
ALTER TABLE review_requests ADD COLUMN repository_policy_version bigint,
    ADD FOREIGN KEY (installation_id, repository_id, repository_policy_version)
        REFERENCES repository_review_policy_versions (installation_id, repository_id, version);

CREATE FUNCTION reject_review_policy_repin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.repository_policy_version IS NOT NULL OR OLD.repository_policy_version IS NOT NULL)
        AND (NEW.installation_id, NEW.repository_id, NEW.repository_policy_version)
            IS DISTINCT FROM (OLD.installation_id, OLD.repository_id, OLD.repository_policy_version) THEN
        RAISE EXCEPTION 'Review policy reference is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER outbox_review_policy_pin BEFORE UPDATE OF installation_id, repository_id, repository_policy_version ON outbox_commands
    FOR EACH ROW EXECUTE FUNCTION reject_review_policy_repin();
CREATE TRIGGER request_review_policy_pin BEFORE UPDATE OF installation_id, repository_id, repository_policy_version ON review_requests
    FOR EACH ROW EXECUTE FUNCTION reject_review_policy_repin();

ALTER TABLE queue_transitions DROP CONSTRAINT queue_transitions_error_code_check;
ALTER TABLE queue_transitions ADD CONSTRAINT queue_transitions_error_code_check
    CHECK (error_code IN ('INTERNAL_ERROR', 'AUTHORIZATION_DENIED', 'AUTHORIZATION_UNAVAILABLE',
        'INVALID_EVENT', 'UNSUPPORTED_COMMAND', 'REMOTE_UNAVAILABLE', 'REMOTE_UNCERTAIN', 'LEASE_EXHAUSTED',
        'POLICY_DENIED'));
