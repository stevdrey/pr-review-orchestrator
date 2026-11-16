package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.Snapshot;

public interface ReviewEvidenceStore {
    /** Authorize inside the atomic snapshot/identity/finding transaction, including retries. */
    boolean ingest(Snapshot snapshot, Runnable authorize);
}
