package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.CommitSha;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.Conflict;
import io.github.stevdrey.prrevieworchestrator.domain.SandboxInlineFix;
import io.github.stevdrey.prrevieworchestrator.ports.SandboxInlineFixStore;

/**
 * Explicit, one-PR entry point for a disposable sandbox. The trusted caller verifies the actual fix,
 * commit relationship and verifier identity before invoking this use case.
 */
public final class VerifySandboxInlineFix {
    private final PullRequestId pr;
    private final String head;
    private final RepositoryAuthorization authorization;
    private final SandboxInlineFixStore store;

    public VerifySandboxInlineFix(PullRequestId pr, String head, RepositoryAuthorization authorization,
            SandboxInlineFixStore store) {
        CommitSha.require(head);
        this.pr = pr;
        this.head = head;
        this.authorization = authorization;
        this.store = store;
    }

    /** Records one previously open finding. An exact retry at the same pinned state returns false. */
    public boolean verify(SandboxInlineFix proof) {
        if (!head.equals(proof.correctiveCommit())) throw new Conflict();
        return store.record(pr, proof, () -> authorization.requireAuthorized(pr.scope()));
    }
}
