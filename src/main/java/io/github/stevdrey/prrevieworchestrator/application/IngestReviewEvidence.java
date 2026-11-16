package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.Snapshot;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewEvidenceStore;

/** Internal trusted-adapter entry point; does not accept public HTTP input or authorize provider calls. */
public final class IngestReviewEvidence {
    private final RepositoryAuthorization authorization;
    private final ReviewEvidenceStore store;

    public IngestReviewEvidence(RepositoryAuthorization authorization, ReviewEvidenceStore store) {
        this.authorization = authorization;
        this.store = store;
    }

    /** @return true for a newly committed snapshot, false for an identical retry. */
    public boolean ingest(Snapshot snapshot) {
        return store.ingest(snapshot, () -> authorization.requireAuthorized(snapshot.pr().scope()));
    }
}
