package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.application.SandboxReviewReconciler;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Event;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.Snapshot;
import org.springframework.jdbc.core.JdbcTemplate;

/** Commits the observation cycle and findings atomically with the fenced inbox completion. */
public final class JdbcSandboxReviewInbox implements SandboxReviewReconciler.Commit {
    private final JdbcTemplate jdbc;
    private final JdbcWorkQueue queue;
    private final JdbcReviewStateStore states;
    private final JdbcReviewEvidenceStore evidence;

    public JdbcSandboxReviewInbox(JdbcTemplate jdbc, JdbcWorkQueue queue, JdbcReviewStateStore states,
            JdbcReviewEvidenceStore evidence) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.states = states;
        this.evidence = evidence;
    }

    @Override
    public boolean reconcile(Event event, Snapshot snapshot, Runnable authorize) {
        return queue.completeLocalEvent(event, authorize, () -> {
            var pr = snapshot.pr();
            PostgresLocks.lockPr(jdbc, pr);
            var current = states.load(pr, false);
            if (current.isPresent() && !snapshot.headSha().equals(current.orElseThrow().headSha())) return;
            jdbc.update("""
                    INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider)
                    VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                    """, snapshot.cycleId(), pr.scope().installationId(), pr.scope().repositoryId(),
                    pr.number(), snapshot.headSha(), SandboxReviewReconciler.STAGE, snapshot.provider());
            evidence.ingestLocked(snapshot);
        });
    }
}
