package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Event;
import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.*;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.Snapshot;
import io.github.stevdrey.prrevieworchestrator.ports.FixCorrelationStore;
import java.util.Objects;

/** Internal use case for approved reconciliation callers. Webhook payloads only route observations. */
public final class CorrelateFixes {
    private final RepositoryAuthorization authorization;
    private final IngestReviewEvidence evidence;
    private final FixCorrelationStore store;

    public CorrelateFixes(RepositoryAuthorization authorization, IngestReviewEvidence evidence, FixCorrelationStore store) {
        this.authorization = Objects.requireNonNull(authorization);
        this.evidence = Objects.requireNonNull(evidence);
        this.store = Objects.requireNonNull(store);
    }

    /** The caller reads expected before fetching the current PR head from its authorized repository. */
    public boolean observeSynchronize(Event event, PullRequestReview expected, Head current) {
        requireScope(event, current.pr());
        if (!"pull_request".equals(event.eventType()) || !"synchronize".equals(event.action())
                || !expected.pr().equals(current.pr())) throw new IllegalArgumentException("Invalid synchronize observation");
        Runnable authorize = () -> authorization.requireAuthorized(current.pr().scope());
        if (!current.sha().equals(event.observedHeadSha())) {
            authorize.run();
            return false;
        }
        return store.observeHead(event.lease().id(), expected, current, authorize);
    }

    /** A complete authoritative re-read can update an older round at a different current PR head. */
    public boolean observeThread(Event event, Snapshot snapshot) {
        requireScope(event, snapshot.pr());
        if (!"pull_request_review_thread".equals(event.eventType())
                || !("resolved".equals(event.action()) || "unresolved".equals(event.action()))) {
            throw new IllegalArgumentException("Invalid thread observation");
        }
        return evidence.ingest(snapshot);
    }

    public Decision assess(Assessment assessment) {
        return store.assess(assessment, () -> authorization.requireAuthorized(assessment.head().pr().scope()));
    }

    private void requireScope(Event event, PullRequestId pr) {
        if (!pr.scope().equals(event.scope()) || !Objects.equals(pr.number(), event.prNumber())) {
            throw new IllegalArgumentException("Mismatched PR observation");
        }
    }
}
