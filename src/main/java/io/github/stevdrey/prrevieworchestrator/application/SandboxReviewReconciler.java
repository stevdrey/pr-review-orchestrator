package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.application.SandboxCiReconciler.Policy;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Event;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.Snapshot;
import io.github.stevdrey.prrevieworchestrator.ports.WorkQueue;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Reconciles evidence for the same operator-pinned PR and head as the sandbox CI route. */
public final class SandboxReviewReconciler {
    public static final String STAGE = "SANDBOX_EVIDENCE";
    private static final Set<String> EVENTS = Set.of("pull_request_review", "pull_request_review_comment",
            "pull_request_review_thread", "issue_comment");

    @FunctionalInterface
    public interface Reader {
        Optional<Snapshot> read(Policy policy, UUID observationId, UUID cycleId);
    }

    @FunctionalInterface
    public interface Commit {
        boolean reconcile(Event event, Snapshot snapshot, Runnable authorize);
    }

    private final Policy policy;
    private final WorkQueue queue;
    private final RepositoryAuthorization authorization;
    private final Reader reader;
    private final Commit commit;
    private final UUID cycleId;

    public SandboxReviewReconciler(Policy policy, WorkQueue queue, RepositoryAuthorization authorization,
            Reader reader, Commit commit) {
        this.policy = policy;
        this.queue = queue;
        this.authorization = authorization;
        this.reader = reader;
        this.commit = commit;
        var pr = policy.pr();
        String cycleKey = STAGE + "/" + pr.scope().installationId() + "/" + pr.scope().repositoryId()
                + "/" + pr.number() + "/" + policy.head();
        cycleId = UUID.nameUUIDFromBytes(cycleKey.getBytes(StandardCharsets.UTF_8));
    }

    public boolean handles(Event event) {
        return EVENTS.contains(event.eventType());
    }

    public boolean complete(Event event) {
        Runnable authorize = () -> authorization.requireAuthorized(event.scope());
        boolean matchingHead = policy.head().equals(event.observedHeadSha())
                || ("issue_comment".equals(event.eventType()) && event.observedHeadSha() == null);
        if (!handles(event) || !policy.pr().scope().equals(event.scope())
                || !Integer.valueOf(policy.pr().number()).equals(event.prNumber()) || !matchingHead) {
            return queue.completeEvent(event, List.of(), authorize);
        }

        var observation = reader.read(policy, event.lease().id(), cycleId);
        if (observation.isEmpty()) return queue.completeEvent(event, List.of(), authorize);
        var snapshot = observation.orElseThrow();
        if (!policy.pr().equals(snapshot.pr()) || !policy.head().equals(snapshot.headSha())
                || !policy.head().equals(snapshot.observedHeadSha()) || !cycleId.equals(snapshot.cycleId())
                || !"github".equals(snapshot.provider()) || !event.lease().id().equals(snapshot.id())) {
            throw new IllegalArgumentException("Review evidence does not match the claimed sandbox delivery");
        }
        return commit.reconcile(event, snapshot, authorize);
    }
}
