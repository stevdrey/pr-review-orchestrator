-- V14 only recorded comparable assessments. Its cached hashes cannot prove
-- that the last assessment was comparable. Keep the snapshots and stop history.
ALTER TABLE review_round_progress ALTER COLUMN finding_sha256 DROP NOT NULL;
UPDATE review_round_progress SET finding_sha256 = NULL WHERE finding_sha256 IS NOT NULL;

-- Repair stale projections from a rollback before this guard was installed.
-- A CI result is current only if its observation followed the latest head visit.
WITH invalidated AS (
    UPDATE pr_review_states s SET ci_status = 'UNKNOWN', version = version + 1,
        state = CASE WHEN state = 'READY_FOR_HUMAN' THEN 'DEEP_REVIEW' ELSE state END,
        resume_state = CASE WHEN resume_state = 'READY_FOR_HUMAN' THEN 'DEEP_REVIEW' ELSE resume_state END
    WHERE s.ci_status <> 'UNKNOWN' AND s.ci_status IS DISTINCT FROM (
        SELECT CASE WHEN t.resulting_head_sha = s.head_sha THEN t.ci_status END
        FROM pr_review_transitions t
        WHERE (t.installation_id, t.repository_id, t.pr_number) = (s.installation_id, s.repository_id, s.pr_number)
            AND t.action IN ('RECONCILE_CI', 'UPDATE_HEAD', 'CONFIRM_HEAD')
        ORDER BY t.resulting_version DESC LIMIT 1
    )
    RETURNING installation_id, repository_id, pr_number, head_sha, version, state
)
INSERT INTO pr_review_transitions (installation_id, repository_id, pr_number, transition_id,
    expected_version, expected_head_sha, action, resulting_version, resulting_head_sha, resulting_state, ci_status)
SELECT installation_id, repository_id, pr_number, gen_random_uuid(), version - 1, head_sha, 'RECONCILE_CI',
    version, head_sha, state, 'UNKNOWN' FROM invalidated;

-- V13 also writes this journal, even though it does not know about ci_status.
-- The current writer already resets CI before appending the head transition.
CREATE FUNCTION invalidate_ci_on_head_visit() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE pr_review_states SET ci_status = 'UNKNOWN'
    WHERE (installation_id, repository_id, pr_number) = (NEW.installation_id, NEW.repository_id, NEW.pr_number)
        AND version = NEW.resulting_version AND head_sha = NEW.resulting_head_sha AND ci_status <> 'UNKNOWN';
    RETURN NEW;
END;
$$;

CREATE TRIGGER pr_review_transitions_invalidate_ci AFTER INSERT ON pr_review_transitions
    FOR EACH ROW WHEN (NEW.action IN ('UPDATE_HEAD', 'CONFIRM_HEAD'))
    EXECUTE FUNCTION invalidate_ci_on_head_visit();
