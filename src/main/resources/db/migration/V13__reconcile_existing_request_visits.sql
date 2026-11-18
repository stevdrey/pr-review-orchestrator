-- Keep the original request key and epoch for retries. Reconcile reservations already made during an active return.
ALTER TABLE review_requests ADD COLUMN reconciled_head_epoch bigint CHECK (reconciled_head_epoch > head_epoch);

WITH current_returns AS (
    SELECT s.installation_id, s.repository_id, s.pr_number, s.head_sha,
        latest.resulting_version AS visit_epoch, latest.occurred_at AS returned_at,
        coalesce((SELECT max(t.resulting_version) FROM pr_review_transitions t
            WHERE (t.installation_id, t.repository_id, t.pr_number) = (s.installation_id, s.repository_id, s.pr_number)
                AND t.action = 'CONFIRM_HEAD' AND t.resulting_head_sha = s.head_sha
                AND t.resulting_version < latest.resulting_version), 0) AS legacy_epoch
    FROM pr_review_states s
    JOIN LATERAL (
        SELECT t.action, t.resulting_head_sha, t.resulting_version, t.occurred_at FROM pr_review_transitions t
        WHERE (t.installation_id, t.repository_id, t.pr_number) = (s.installation_id, s.repository_id, s.pr_number)
            AND t.action IN ('UPDATE_HEAD', 'CONFIRM_HEAD')
        ORDER BY t.resulting_version DESC LIMIT 1
    ) latest ON latest.action = 'UPDATE_HEAD' AND latest.resulting_head_sha = s.head_sha
    WHERE EXISTS (SELECT 1 FROM pr_review_transitions t
        WHERE (t.installation_id, t.repository_id, t.pr_number) = (s.installation_id, s.repository_id, s.pr_number)
            AND t.action = 'UPDATE_HEAD' AND t.expected_head_sha = s.head_sha
            AND t.resulting_version < latest.resulting_version)
)
UPDATE review_requests r SET reconciled_head_epoch = v.visit_epoch
FROM current_returns v
WHERE (r.installation_id, r.repository_id, r.pr_number, r.head_sha)
    = (v.installation_id, v.repository_id, v.pr_number, v.head_sha)
    AND r.head_epoch = v.legacy_epoch AND r.created_at >= v.returned_at
    AND NOT EXISTS (SELECT 1 FROM review_requests current_request
        WHERE (current_request.installation_id, current_request.repository_id, current_request.pr_number,
            current_request.head_sha, current_request.stage, current_request.provider)
            = (r.installation_id, r.repository_id, r.pr_number, r.head_sha, r.stage, r.provider)
            AND current_request.head_epoch = v.visit_epoch);

CREATE UNIQUE INDEX review_requests_visit_key ON review_requests
    (installation_id, repository_id, pr_number, head_sha, stage, provider, coalesce(reconciled_head_epoch, head_epoch));
