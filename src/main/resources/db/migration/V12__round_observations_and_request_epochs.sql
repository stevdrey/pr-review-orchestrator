-- Same-head observation time of each occurrence's current source revision. NULL means the revision was
-- only seen in a read at a different PR head and is no evidence for its own round.
ALTER TABLE findings ADD COLUMN round_observed_at timestamptz;
UPDATE findings f SET round_observed_at = CASE
        WHEN latest.observed_head_sha IS NULL OR latest.observed_head_sha = latest.head_sha THEN latest.captured_at
        ELSE (SELECT max(prior.captured_at)
            FROM review_evidence_snapshots prior
            JOIN review_evidence_sources old_source ON old_source.snapshot_id = prior.id AND old_source.source_id = f.source_id
            JOIN review_evidence_sources latest_source
                ON latest_source.snapshot_id = latest.id AND latest_source.source_id = f.source_id
            WHERE prior.review_cycle_id = f.review_cycle_id AND prior.provider = f.source_provider
                AND prior.captured_at <= latest.captured_at
                AND (prior.observed_head_sha IS NULL OR prior.observed_head_sha = prior.head_sha)
                AND old_source.source_kind = f.source_kind
                AND old_source.body_sha256 = latest_source.body_sha256
                AND old_source.source_updated_at IS NOT DISTINCT FROM latest_source.source_updated_at
                AND old_source.feedback = latest_source.feedback)
    END
FROM review_evidence_snapshots latest WHERE latest.id = f.evidence_snapshot_id;

-- A same-head confirmation opens a new request epoch for that SHA; epoch 0 keeps the legacy key.
ALTER TABLE review_requests ADD COLUMN head_epoch bigint NOT NULL DEFAULT 0 CHECK (head_epoch >= 0);
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'review_requests'::regclass AND (
            (contype = 'c' AND pg_get_constraintdef(oid) LIKE '%request_key =%')
            OR (contype = 'u' AND pg_get_constraintdef(oid)
                = 'UNIQUE (installation_id, repository_id, pr_number, head_sha, stage, provider)'))
    LOOP
        EXECUTE format('ALTER TABLE review_requests DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
ALTER TABLE review_requests ADD CONSTRAINT review_requests_key_check CHECK (request_key
    = installation_id || '/' || repository_id || '/' || pr_number || '/' || head_sha || '/' || stage || '/' || provider
        || CASE WHEN head_epoch = 0 THEN '' ELSE '/' || head_epoch END);
ALTER TABLE review_requests ADD CONSTRAINT review_requests_epoch_key
    UNIQUE (installation_id, repository_id, pr_number, head_sha, stage, provider, head_epoch);
