package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.CiEvidence;
import io.github.stevdrey.prrevieworchestrator.domain.CommitSha;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Event;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.ports.WorkQueue;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** CI-only sandbox observations for an explicitly selected PR, head and workflow policy. */
public final class SandboxCiReconciler {
    public record Policy(PullRequestId pr, RepositoryScope readScope, String repository, String head,
            Set<Long> workflowIds) {
        public Policy {
            CommitSha.require(head);
            workflowIds = Set.copyOf(workflowIds);
            if (readScope.repositoryId() != pr.scope().repositoryId() || repository == null
                    || !repository.matches("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}")
                    || repository.endsWith("/.") || repository.endsWith("/..")
                    || workflowIds.isEmpty() || workflowIds.size() > 4 || workflowIds.stream().anyMatch(id -> id <= 0)) {
                throw new IllegalArgumentException("An exact sandbox PR and one to four workflow IDs are required");
            }
        }
    }

    @FunctionalInterface
    public interface Reader {
        Optional<CiEvidence> read(Policy policy, UUID observationId);
    }

    @FunctionalInterface
    public interface Commit {
        boolean reconcile(Event event, Optional<PullRequestReview> expected, CiEvidence evidence, Runnable authorize);
    }

    private final Policy policy;
    private final WorkQueue queue;
    private final RepositoryAuthorization authorization;
    private final ReviewLifecycle lifecycle;
    private final Reader reader;
    private final Commit commit;

    public SandboxCiReconciler(Policy policy, WorkQueue queue, RepositoryAuthorization authorization,
            ReviewLifecycle lifecycle, Reader reader, Commit commit) {
        this.policy = policy;
        this.queue = queue;
        this.authorization = authorization;
        this.lifecycle = lifecycle;
        this.reader = reader;
        this.commit = commit;
    }

    public boolean complete(Event event) {
        Runnable authorize = () -> authorization.requireAuthorized(event.scope());
        if (!policy.pr().scope().equals(event.scope()) || !policy.head().equals(event.observedHeadSha())
                || !("check_run".equals(event.eventType()) || "workflow_run".equals(event.eventType()))
                || event.prNumber() != null && event.prNumber().intValue() != policy.pr().number()) {
            return queue.completeEvent(event, List.of(), authorize);
        }
        var expected = lifecycle.find(policy.pr());
        if (expected.isPresent() && !policy.head().equals(expected.orElseThrow().headSha())) {
            return queue.completeEvent(event, List.of(), authorize);
        }

        // Webhook metadata wakes the reader. PR membership, statuses and the current attempts come from GitHub.
        var observation = reader.read(policy, event.lease().id());
        if (observation.isEmpty()) return queue.completeEvent(event, List.of(), authorize);
        var evidence = observation.orElseThrow();
        if (!policy.pr().equals(evidence.pr())) throw new IllegalArgumentException("CI evidence has an unexpected PR");
        return commit.reconcile(event, expected, evidence, authorize);
    }
}
