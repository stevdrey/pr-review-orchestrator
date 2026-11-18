-- Existing snapshots used one SHA for both the round and the PR read. Preserve their fingerprints.
ALTER TABLE review_evidence_snapshots ADD COLUMN observed_head_sha text
    CHECK (observed_head_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$');
