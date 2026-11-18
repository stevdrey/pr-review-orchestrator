-- Preserve the exact provider/stage/request/evidence used by accepted clean transitions.
-- Existing clean fields are legacy trusted decisions; do not invent provenance for them.
ALTER TABLE review_requests ADD CONSTRAINT review_requests_outcome_scope_key
    UNIQUE (id, installation_id, repository_id, pr_number, head_sha, stage, provider);
ALTER TABLE review_cycles ADD CONSTRAINT review_cycles_outcome_scope_key
    UNIQUE (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider);

CREATE TABLE review_clean_evidence (
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    pr_number integer NOT NULL,
    transition_id uuid NOT NULL,
    request_id uuid NOT NULL,
    cycle_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    head_sha text NOT NULL,
    stage text NOT NULL CHECK (stage IN ('BASELINE', 'DEEP')),
    provider text NOT NULL,
    evidence_provider text NOT NULL,
    review_source_id text NOT NULL,
    reviewer text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (installation_id, repository_id, pr_number, transition_id),
    FOREIGN KEY (installation_id, repository_id, pr_number, transition_id)
        REFERENCES pr_review_transitions (installation_id, repository_id, pr_number, transition_id),
    FOREIGN KEY (request_id, installation_id, repository_id, pr_number, head_sha, stage, provider)
        REFERENCES review_requests (id, installation_id, repository_id, pr_number, head_sha, stage, provider),
    FOREIGN KEY (cycle_id, installation_id, repository_id, pr_number, head_sha, stage, provider)
        REFERENCES review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider),
    FOREIGN KEY (snapshot_id, cycle_id) REFERENCES review_evidence_snapshots (id, review_cycle_id),
    FOREIGN KEY (snapshot_id, review_source_id) REFERENCES review_evidence_sources (snapshot_id, source_id)
);
CREATE INDEX review_clean_evidence_request_idx ON review_clean_evidence (request_id);
CREATE INDEX review_clean_evidence_cycle_idx ON review_clean_evidence (cycle_id);
CREATE INDEX review_clean_evidence_source_idx ON review_clean_evidence (snapshot_id, review_source_id);

-- Database-assigned ingestion time keeps freshness comparisons on one (PostgreSQL) clock.
-- Existing snapshots keep NULL: no ingestion time is invented, so they cannot establish clean evidence.
ALTER TABLE review_evidence_snapshots ADD COLUMN ingested_at timestamptz;
ALTER TABLE review_evidence_snapshots ALTER COLUMN ingested_at SET DEFAULT clock_timestamp();

-- The transition INSERT happens under the PR lock, potentially long after the transaction began.
ALTER TABLE pr_review_transitions ALTER COLUMN occurred_at SET DEFAULT clock_timestamp();
