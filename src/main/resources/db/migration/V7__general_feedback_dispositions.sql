-- General feedback has no GitHub thread. Keep its assessment with each immutable source revision.
ALTER TABLE review_evidence_sources DROP CONSTRAINT review_evidence_sources_source_kind_check;
ALTER TABLE review_evidence_sources ADD CHECK (source_kind IN ('REVIEW', 'REVIEW_COMMENT', 'REVIEW_THREAD', 'ISSUE_COMMENT'));
ALTER TABLE review_evidence_sources DROP CONSTRAINT review_evidence_sources_check;
ALTER TABLE review_evidence_sources DROP CONSTRAINT review_evidence_sources_check1;
ALTER TABLE review_evidence_sources DROP CONSTRAINT review_evidence_sources_check2;
ALTER TABLE review_evidence_sources ADD CHECK ((source_kind IN ('REVIEW_COMMENT', 'REVIEW_THREAD')) = (thread_id IS NOT NULL));
ALTER TABLE review_evidence_sources ADD CHECK ((source_kind IN ('REVIEW_COMMENT', 'REVIEW_THREAD')) = (thread_resolved IS NOT NULL));
ALTER TABLE review_evidence_sources ADD CHECK ((source_kind IN ('REVIEW_COMMENT', 'REVIEW_THREAD')) = (thread_outdated IS NOT NULL));
ALTER TABLE review_evidence_sources ADD COLUMN feedback text NOT NULL DEFAULT 'NONE'
    CHECK (feedback IN ('NONE', 'ACTIONABLE', 'NEEDS_HUMAN'));
-- V6 did not classify review bodies; preserve uncertainty without inventing a clean verdict.
UPDATE review_evidence_sources SET feedback = 'NEEDS_HUMAN' WHERE source_kind = 'REVIEW'
    AND body_sha256 <> 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';
ALTER TABLE review_evidence_sources ADD CHECK (source_kind IN ('REVIEW', 'ISSUE_COMMENT') OR feedback = 'NONE');
ALTER TABLE finding_identities DROP CONSTRAINT finding_identities_source_kind_check;
ALTER TABLE finding_identities ADD CHECK (source_kind IN ('REVIEW_COMMENT', 'REVIEW', 'ISSUE_COMMENT'));

ALTER TABLE review_evidence_snapshots ADD CONSTRAINT review_evidence_snapshot_scope_key
    UNIQUE (id, installation_id, repository_id, pr_number, provider);
ALTER TABLE review_evidence_sources ADD CONSTRAINT review_evidence_source_kind_key
    UNIQUE (snapshot_id, source_id, source_kind);

CREATE TABLE general_feedback_dispositions (
    id uuid PRIMARY KEY,
    disposition_order bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    stable_finding_id uuid NOT NULL,
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    pr_number integer NOT NULL,
    provider text NOT NULL,
    source_kind text NOT NULL CHECK (source_kind IN ('REVIEW', 'ISSUE_COMMENT')),
    source_id text NOT NULL,
    snapshot_id uuid NOT NULL,
    head_sha text NOT NULL CHECK (head_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    head_version bigint NOT NULL CHECK (head_version >= 0),
    action text NOT NULL CHECK (action IN ('ACKNOWLEDGE', 'CLAIM_FIXED', 'DISMISS')),
    actor text NOT NULL CHECK (btrim(actor) <> '' AND length(actor) <= 1024),
    reason text NOT NULL CHECK (btrim(reason) <> '' AND length(reason) <= 4096),
    evidence_sha text CHECK (evidence_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (stable_finding_id, installation_id, repository_id, pr_number, provider, source_kind, source_id)
        REFERENCES finding_identities (id, installation_id, repository_id, pr_number, provider, source_kind, source_id),
    FOREIGN KEY (snapshot_id, installation_id, repository_id, pr_number, provider)
        REFERENCES review_evidence_snapshots (id, installation_id, repository_id, pr_number, provider),
    FOREIGN KEY (snapshot_id, source_id, source_kind)
        REFERENCES review_evidence_sources (snapshot_id, source_id, source_kind),
    CHECK ((action = 'CLAIM_FIXED') = (evidence_sha IS NOT NULL))
);
CREATE INDEX general_feedback_dispositions_finding_idx ON general_feedback_dispositions (stable_finding_id, disposition_order DESC);

-- Preserve exact-retry compatibility with snapshots fingerprinted by V6 application code.
ALTER TABLE review_evidence_snapshots ADD COLUMN fingerprint_version integer NOT NULL DEFAULT 1 CHECK (fingerprint_version IN (1, 2));

-- Existing non-empty review bodies were observations only. Escalate them conservatively on upgrade.
INSERT INTO finding_identities (id, installation_id, repository_id, pr_number, provider, source_kind, source_id)
SELECT gen_random_uuid(), installation_id, repository_id, pr_number, provider, 'REVIEW', source_id
FROM (SELECT DISTINCT o.installation_id, o.repository_id, o.pr_number, o.provider, s.source_id
    FROM review_evidence_sources s JOIN review_evidence_snapshots o ON o.id = s.snapshot_id
    WHERE s.source_kind = 'REVIEW' AND s.feedback = 'NEEDS_HUMAN') existing
ON CONFLICT DO NOTHING;
-- For each tracked identity, retain the latest revision in every round, including blank-only rounds.
-- The scoped identity join below excludes reviews that were always blank for that PR/provider.
INSERT INTO findings (id, review_cycle_id, installation_id, repository_id, pr_number, reviewed_sha,
    source_provider, source_kind, source_id, body_ref, stable_finding_id, evidence_snapshot_id)
SELECT gen_random_uuid(), o.review_cycle_id, o.installation_id, o.repository_id, o.pr_number, o.head_sha,
    o.provider, 'REVIEW', s.source_id, 'github-node:' || s.source_id, i.id, s.snapshot_id
FROM (SELECT DISTINCT ON (o.review_cycle_id, o.provider, s.source_id) s.*
    FROM review_evidence_sources s JOIN review_evidence_snapshots o ON o.id = s.snapshot_id
    WHERE s.source_kind = 'REVIEW'
    ORDER BY o.review_cycle_id, o.provider, s.source_id, o.captured_at DESC) s
JOIN review_evidence_snapshots o ON o.id = s.snapshot_id
JOIN finding_identities i ON i.installation_id = o.installation_id AND i.repository_id = o.repository_id
    AND i.pr_number = o.pr_number AND i.provider = o.provider AND i.source_kind = 'REVIEW' AND i.source_id = s.source_id
ON CONFLICT DO NOTHING;

-- Previously clean aggregates cannot remain ready when upgraded review bodies need disposition.
WITH invalidated AS (
    UPDATE pr_review_states p SET version = version + 1,
        state = CASE WHEN state = 'PAUSED' THEN 'PAUSED' ELSE 'ACTION_REQUIRED' END,
        resume_state = CASE WHEN state = 'PAUSED' THEN 'ACTION_REQUIRED' END,
        baseline_clean_sha = NULL, deep_clean_sha = NULL
    WHERE baseline_clean_sha IS NOT NULL AND EXISTS (SELECT 1 FROM findings f
        JOIN review_cycles c ON c.id = f.review_cycle_id
        WHERE c.installation_id = p.installation_id AND c.repository_id = p.repository_id
            AND c.pr_number = p.pr_number AND f.source_kind IN ('REVIEW', 'ISSUE_COMMENT')
            AND (f.stable_finding_id IS NOT NULL OR f.status IN ('OPEN', 'ACKNOWLEDGED')))
    RETURNING *
)
INSERT INTO pr_review_transitions (installation_id, repository_id, pr_number, transition_id,
    expected_version, expected_head_sha, action, resulting_version, resulting_head_sha, resulting_state)
SELECT installation_id, repository_id, pr_number, gen_random_uuid(), version - 1, head_sha,
    'BLOCKING_FINDINGS', version, head_sha, state FROM invalidated;

CREATE INDEX findings_general_scope_idx ON findings (installation_id, repository_id, pr_number, stable_finding_id)
    WHERE source_kind IN ('REVIEW', 'ISSUE_COMMENT');
