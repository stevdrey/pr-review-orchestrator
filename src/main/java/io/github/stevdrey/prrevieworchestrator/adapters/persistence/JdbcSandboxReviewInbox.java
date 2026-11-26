package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.application.SandboxReviewReconciler;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Event;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.Kind;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.Snapshot;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewEvidence.Source;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
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
            var rounds = splitByThreadOrigin(snapshot);
            var observationIds = rounds.stream().map(Snapshot::id).collect(Collectors.toSet());
            for (var round : rounds) {
                jdbc.update("""
                        INSERT INTO review_cycles (id, installation_id, repository_id, pr_number, head_sha, stage, review_provider)
                        VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                        """, round.cycleId(), pr.scope().installationId(), pr.scope().repositoryId(),
                        pr.number(), round.headSha(), SandboxReviewReconciler.STAGE, round.provider());
                evidence.ingestLocked(round, observationIds);
            }
        });
    }

    private List<Snapshot> splitByThreadOrigin(Snapshot observation) {
        String currentHead = observation.headSha();
        var origins = new HashMap<String, String>();
        for (var source : observation.sources()) {
            if (source.kind() == Kind.REVIEW_COMMENT && source.replyToId() == null) {
                String origin = source.originalSha() == null ? source.reviewedSha() : source.originalSha();
                origins.put(source.threadId(), Objects.requireNonNullElse(origin, currentHead));
            }
        }

        // A complete read includes old threads. Keep each root, its replies and its thread in
        // the originating round so refreshes update the same finding occurrence.
        Map<String, List<Source>> byHead = new HashMap<>();
        byHead.put(currentHead, new ArrayList<>());
        for (var source : observation.sources()) {
            String head = origins.getOrDefault(source.threadId(), currentHead);
            byHead.computeIfAbsent(head, ignored -> new ArrayList<>()).add(source);
        }
        return byHead.entrySet().stream().map(round -> {
            String head = round.getKey();
            UUID id = head.equals(currentHead) ? observation.id() : UUID.nameUUIDFromBytes(
                    ("sandbox-review-round/" + observation.id() + "/" + head).getBytes(StandardCharsets.UTF_8));
            return new Snapshot(id, SandboxReviewReconciler.cycleId(observation.pr(), head), observation.pr(),
                    observation.provider(), head, observation.observedHeadSha(), observation.capturedAt(), round.getValue());
        }).toList();
    }
}
