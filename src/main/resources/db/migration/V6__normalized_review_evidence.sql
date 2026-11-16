-- Retain immutable metadata revisions, not raw bodies or diffs. Existing V1 finding rows stay valid.
ALTER TABLE review_cycles ADD CONSTRAINT review_cycles_pr_scope_key
    UNIQUE (id, installation_id, repository_id, pr_number, head_sha);

CREATE TABLE review_evidence_snapshots (
    id uuid PRIMARY KEY,
    review_cycle_id uuid NOT NULL,
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    pr_number integer NOT NULL,
    head_sha text NOT NULL,
    provider text NOT NULL CHECK (btrim(provider) <> ''),
    captured_at timestamptz NOT NULL,
    fingerprint text NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    FOREIGN KEY (review_cycle_id, installation_id, repository_id, pr_number, head_sha)
        REFERENCES review_cycles (id, installation_id, repository_id, pr_number, head_sha),
    UNIQUE (id, review_cycle_id),
    UNIQUE (review_cycle_id, captured_at)
);

CREATE TABLE review_evidence_sources (
    snapshot_id uuid NOT NULL REFERENCES review_evidence_snapshots (id),
    source_id text NOT NULL,
    source_kind text NOT NULL CHECK (source_kind IN ('REVIEW', 'REVIEW_COMMENT', 'REVIEW_THREAD')),
    review_id text,
    thread_id text,
    reply_to_id text,
    author_login text,
    reviewed_sha text CHECK (reviewed_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    original_sha text CHECK (original_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    body_sha256 text CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
    source_updated_at timestamptz,
    review_state text,
    thread_resolved boolean,
    thread_outdated boolean,
    path text,
    line integer CHECK (line > 0),
    original_line integer CHECK (original_line > 0),
    severity text NOT NULL CHECK (severity IN ('UNKNOWN', 'INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    PRIMARY KEY (snapshot_id, source_id),
    CHECK ((source_kind <> 'REVIEW') = (thread_id IS NOT NULL)),
    CHECK ((source_kind <> 'REVIEW') = (thread_resolved IS NOT NULL)),
    CHECK ((source_kind <> 'REVIEW') = (thread_outdated IS NOT NULL)),
    -- reviewed_sha/review_id may be NULL when GitHub no longer resolves the commit/review; such sources are stale.
    CHECK (source_kind = 'REVIEW_THREAD' OR (body_sha256 IS NOT NULL AND source_updated_at IS NOT NULL)),
    CHECK (source_kind <> 'REVIEW' OR review_id IS NOT NULL)
);

-- Stable identity is scoped by installation/repository/PR/provider/source; never by mutable text/line.
CREATE TABLE finding_identities (
    id uuid PRIMARY KEY,
    installation_id bigint NOT NULL,
    repository_id bigint NOT NULL,
    pr_number integer NOT NULL CHECK (pr_number > 0),
    provider text NOT NULL,
    source_kind text NOT NULL CHECK (source_kind = 'REVIEW_COMMENT'),
    source_id text NOT NULL,
    FOREIGN KEY (installation_id, repository_id)
        REFERENCES installation_repositories (installation_id, repository_id),
    UNIQUE (installation_id, repository_id, pr_number, provider, source_kind, source_id),
    UNIQUE (id, installation_id, repository_id, pr_number, provider, source_kind, source_id)
);

ALTER TABLE findings ADD COLUMN stable_finding_id uuid;
ALTER TABLE findings ADD COLUMN evidence_snapshot_id uuid;
ALTER TABLE findings ADD COLUMN pr_number integer;
ALTER TABLE findings ADD CONSTRAINT findings_stable_scope_fk
    FOREIGN KEY (stable_finding_id, installation_id, repository_id, pr_number, source_provider, source_kind, source_id)
        REFERENCES finding_identities (id, installation_id, repository_id, pr_number, provider, source_kind, source_id);
ALTER TABLE findings ADD CONSTRAINT findings_evidence_cycle_fk
    FOREIGN KEY (evidence_snapshot_id, review_cycle_id) REFERENCES review_evidence_snapshots (id, review_cycle_id);
ALTER TABLE findings ADD CONSTRAINT findings_evidence_source_fk
    FOREIGN KEY (evidence_snapshot_id, source_id) REFERENCES review_evidence_sources (snapshot_id, source_id);
ALTER TABLE findings ADD CONSTRAINT findings_pr_cycle_fk
    FOREIGN KEY (review_cycle_id, installation_id, repository_id, pr_number, reviewed_sha)
        REFERENCES review_cycles (id, installation_id, repository_id, pr_number, head_sha);
ALTER TABLE findings ADD CONSTRAINT findings_evidence_pair_check CHECK (
    (stable_finding_id IS NULL AND evidence_snapshot_id IS NULL AND pr_number IS NULL) OR
    (stable_finding_id IS NOT NULL AND evidence_snapshot_id IS NOT NULL AND pr_number IS NOT NULL));
CREATE INDEX findings_stable_idx ON findings (stable_finding_id);
CREATE INDEX findings_evidence_idx ON findings (evidence_snapshot_id, source_id);
