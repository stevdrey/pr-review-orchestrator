package io.github.stevdrey.prrevieworchestrator.application;

import static io.github.stevdrey.prrevieworchestrator.domain.DurableWork.REVIEW_ASSESSMENT_EVENT;

import io.github.stevdrey.prrevieworchestrator.application.SandboxCiReconciler.Policy;
import io.github.stevdrey.prrevieworchestrator.domain.CiEvidence;
import io.github.stevdrey.prrevieworchestrator.domain.CommitSha;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Event;
import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Comparison;
import io.github.stevdrey.prrevieworchestrator.domain.FixCorrelation.Head;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview;
import io.github.stevdrey.prrevieworchestrator.ports.WorkQueue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Signed synchronize events, CI wake-ups and due assessments for one explicitly selected sandbox PR. */
public final class SandboxReviewPlanning {
    public interface Reader extends SandboxCiReconciler.Reader {
        Optional<Head> readHead(Policy policy);
        Comparison compare(Policy policy, String baseSha);
    }

    public sealed interface Preparation {
        record Ready(long version, String baseSha) implements Preparation {}
        enum Finished implements Preparation { PROCESSED, LEASE_LOST }
    }

    public interface Commit {
        Preparation prepare(Event event, Optional<PullRequestReview> expected, Head head, Runnable authorize);
        boolean finish(Event event, Preparation.Ready ready, Head head, CiEvidence ci, Comparison comparison,
                Runnable authorize);
    }

    private final Policy policy;
    private final WorkQueue queue;
    private final RepositoryAuthorization ingressAuthorization;
    private final RepositoryAuthorization readAuthorization;
    private final ReviewLifecycle lifecycle;
    private final Reader reader;
    private final Commit commit;

    public SandboxReviewPlanning(Policy policy, WorkQueue queue, RepositoryAuthorization ingressAuthorization,
            RepositoryAuthorization readAuthorization, ReviewLifecycle lifecycle, Reader reader, Commit commit) {
        this.policy = policy;
        this.queue = queue;
        this.ingressAuthorization = ingressAuthorization;
        this.readAuthorization = readAuthorization;
        this.lifecycle = lifecycle;
        this.reader = reader;
        this.commit = commit;
    }

    public boolean handles(Event event) {
        return "check_run".equals(event.eventType()) || "workflow_run".equals(event.eventType())
                || isSynchronize(event) || isAssessment(event);
    }

    public static boolean isSynchronize(Event event) {
        return "pull_request".equals(event.eventType()) && "synchronize".equals(event.action());
    }

    public static boolean isAssessment(Event event) {
        return REVIEW_ASSESSMENT_EVENT.equals(event.eventType()) && event.action() == null;
    }

    public boolean complete(Event event) {
        Runnable authorizeIngress = () -> ingressAuthorization.requireAuthorized(event.scope());
        boolean sync = isSynchronize(event);
        if (!handles(event) || !policy.pr().scope().equals(event.scope()) || !CommitSha.isValid(event.observedHeadSha())
                || (sync || event.prNumber() != null) && !Integer.valueOf(policy.pr().number()).equals(event.prNumber())) {
            return queue.completeEvent(event, List.of(), authorizeIngress);
        }
        Runnable authorize = () -> {
            authorizeIngress.run();
            readAuthorization.requireAuthorized(policy.readScope());
        };
        authorize.run();

        // Capture the version before the remote read so a concurrent newer observation wins at commit.
        var expected = lifecycle.find(policy.pr());
        if (isAssessment(event) && expected.isEmpty()) return queue.completeEvent(event, List.of(), authorize);
        if (!sync && !expected.map(PullRequestReview::headSha).orElse(policy.head()).equals(event.observedHeadSha())) {
            return queue.completeEvent(event, List.of(), authorize);
        }
        var atHead = new Policy(policy.pr(), policy.readScope(), policy.repository(), event.observedHeadSha(), policy.workflowIds());
        var observation = reader.readHead(atHead).filter(head -> policy.pr().equals(head.pr())
                && atHead.head().equals(head.sha()));
        if (observation.isEmpty()) return queue.completeEvent(event, List.of(), authorize);
        var head = observation.orElseThrow();
        return switch (commit.prepare(event, expected, head, authorize)) {
            case Preparation.Finished finished -> finished == Preparation.Finished.PROCESSED;
            case Preparation.Ready ready -> evaluate(event, ready, atHead, head, authorize);
        };
    }

    private boolean evaluate(Event event, Preparation.Ready ready, Policy atHead, Head head, Runnable authorize) {
        var comparison = ready.baseSha() == null ? null : reader.compare(atHead, ready.baseSha());
        // The CI reader pins both attempts and checks the PR head again after the last remote observation.
        var ci = reader.read(atHead, isSynchronize(event) ? UUID.randomUUID() : event.lease().id());
        if (ci.isEmpty()) return queue.completeEvent(event, List.of(), authorize);
        return commit.finish(event, ready, head, ci.orElseThrow(), comparison, authorize);
    }
}
