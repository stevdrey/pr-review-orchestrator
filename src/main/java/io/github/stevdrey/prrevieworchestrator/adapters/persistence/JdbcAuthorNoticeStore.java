package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.AuthorNotice.*;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationDenied;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationUnavailable;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.ports.AuthorNoticePublisher;
import io.github.stevdrey.prrevieworchestrator.ports.AuthorNoticeStore;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToLongFunction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcAuthorNoticeStore implements AuthorNoticeStore {
    private static final Duration SEND_DEADLINE = Duration.ofSeconds(8);
    // Allow the 3-second HTTP deadline plus time to store the receipt and commit.
    private static final Duration MIN_POST_BUDGET = Duration.ofSeconds(4);
    private final JdbcTemplate jdbc;
    private final JdbcReviewStateStore states;
    private final TransactionTemplate sends;

    public JdbcAuthorNoticeStore(JdbcTemplate jdbc, JdbcReviewStateStore states, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.states = states;
        sends = new TransactionTemplate(manager);
        sends.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        sends.setTimeout(Math.toIntExact(SEND_DEADLINE.toSeconds()));
    }

    @Override
    public Optional<Job> claim() {
        return jdbc.query("""
                WITH next AS (
                    SELECT id FROM author_notifications
                    WHERE status = 'PENDING' AND attempts < 3 AND available_at <= now()
                    ORDER BY available_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE author_notifications n SET attempts = attempts + 1, available_at = now() + interval '1 minute'
                FROM next WHERE n.id = next.id
                RETURNING n.*
                """, (rs, row) -> new Job(rs.getLong("id"),
                new PullRequestId(new RepositoryScope(rs.getLong("installation_id"), rs.getLong("repository_id")),
                        rs.getInt("pr_number")),
                rs.getString("head_sha"), State.valueOf(rs.getString("state")),
                rs.getLong("state_version"), rs.getInt("attempts"))).stream().findFirst();
    }

    @Override
    public boolean deliver(Job job, long recipientId, Runnable authorize, ToLongFunction<Evidence> post) {
        if (!reserve(job, recipientId, authorize)) return false;

        // Once the POST may have started, a rollback or lost response keeps the committed permit in place.
        var mayHaveSent = new AtomicBoolean();
        try {
            boolean sent = Boolean.TRUE.equals(sends.execute(status -> {
                try {
                    long start = System.nanoTime();
                    PostgresDeadlines.apply(jdbc, SEND_DEADLINE);
                    // JdbcInstallationAuthorization holds FOR SHARE locks until this transaction ends.
                    authorize.run();
                    // Recheck without the PR lock: lifecycle writers must not wait on the GitHub POST.
                    var current = currentTransition(job, false);
                    if (current.isEmpty()) return false;
                    Evidence evidence = evidence(job, current.get());
                    if (System.nanoTime() - start >= SEND_DEADLINE.minus(MIN_POST_BUDGET).toNanos()) {
                        throw new IllegalStateException("Author notification send deadline exhausted");
                    }
                    mayHaveSent.set(true);
                    long commentId;
                    try {
                        commentId = post.applyAsLong(evidence);
                    } catch (AuthorNoticePublisher.NotPosted notPosted) {
                        mayHaveSent.set(false);
                        throw notPosted;
                    }
                    jdbc.update("""
                            UPDATE author_notifications SET status = 'SENT', comment_id = ?
                            WHERE id = ? AND status = 'ATTEMPTED' AND attempts = ?
                            """, commentId, job.id(), job.attempt());
                    return true;
                } catch (RuntimeException failure) {
                    throw sanitized(failure);
                }
            }));
            if (!sent) release(job);
            return sent;
        } catch (RuntimeException failure) {
            if (!mayHaveSent.get()) release(job);
            throw sanitized(failure);
        }
    }

    /** Best effort: return a permit that provably never reached GitHub; a failure here keeps it (fail closed). */
    private void release(Job job) {
        try {
            jdbc.update("""
                    UPDATE author_notifications SET status = 'PENDING', recipient_id = NULL
                    WHERE id = ? AND status = 'ATTEMPTED' AND attempts = ? AND comment_id IS NULL
                    """, job.id(), job.attempt());
        } catch (RuntimeException ignored) {
            // The permit stays ATTEMPTED; never surface database diagnostics.
        }
    }

    @Override
    public boolean isCurrent(Job job, Runnable authorize) {
        return states.locked(job.pr(), authorize, () -> currentTransition(job, true).isPresent());
    }

    private boolean reserve(Job job, long recipientId, Runnable authorize) {
        var pr = job.pr();
        return states.locked(pr, authorize, () -> {
            if (currentTransition(job, true).isEmpty()) return false;
            // Installations may change, but two installations must never publish the same effect.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {},
                    "author-notice:" + pr.scope().repositoryId() + ":" + pr.number());
            authorize.run();
            int reserved = jdbc.update("""
                    UPDATE author_notifications n SET status = 'ATTEMPTED', recipient_id = ?
                    WHERE n.id = ? AND n.status = 'PENDING' AND n.attempts = ? AND n.available_at > clock_timestamp()
                        AND NOT EXISTS (
                            SELECT 1 FROM author_notifications prior
                            WHERE prior.repository_id = n.repository_id AND prior.pr_number = n.pr_number
                                AND prior.state = n.state AND prior.head_sha = n.head_sha AND prior.recipient_id = ?
                        )
                    """, recipientId, job.id(), job.attempt(), recipientId);
            return reserved == 1;
        });
    }

    private Optional<PullRequestReview> currentTransition(Job job, boolean lock) {
        return states.load(job.pr(), lock).filter(current ->
                current.headSha().equals(job.headSha()) && current.state() == job.state() && !superseded(job));
    }

    private Evidence evidence(Job job, PullRequestReview current) {
        var providers = job.state() == State.READY_FOR_HUMAN ? cleanProviders(job) : Map.<String, ProviderEvidence>of();
        return new Evidence(current.ci(), providers.getOrDefault("BASELINE", ProviderEvidence.UNAVAILABLE),
                providers.getOrDefault("DEEP", ProviderEvidence.UNAVAILABLE));
    }

    private boolean superseded(Job job) {
        var pr = job.pr();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pr_review_transitions
                    WHERE installation_id = ? AND repository_id = ? AND pr_number = ? AND resulting_version > ?
                        AND (resulting_state <> ? OR action IN ('UPDATE_HEAD', 'CONFIRM_HEAD'))
                )
                """, Boolean.class, pr.scope().installationId(), pr.scope().repositoryId(),
                pr.number(), job.version(), job.state().name()));
    }

    private Map<String, ProviderEvidence> cleanProviders(Job job) {
        var pr = job.pr();
        return jdbc.query("""
                SELECT DISTINCT ON (e.stage) e.stage, e.provider = 'codex' AS codex
                FROM review_clean_evidence e JOIN pr_review_transitions t
                    USING (installation_id, repository_id, pr_number, transition_id)
                WHERE e.installation_id = ? AND e.repository_id = ? AND e.pr_number = ? AND e.head_sha = ?
                    AND t.resulting_version < ?
                    AND NOT EXISTS (
                        SELECT 1 FROM pr_review_transitions reset
                        WHERE (reset.installation_id, reset.repository_id, reset.pr_number) =
                            (e.installation_id, e.repository_id, e.pr_number)
                            AND reset.resulting_version > t.resulting_version
                            AND reset.resulting_version <= ?
                            AND reset.action IN ('UPDATE_HEAD', 'CONFIRM_HEAD', 'BLOCKING_FINDINGS')
                    )
                ORDER BY e.stage, t.resulting_version DESC
                """, rs -> {
                    var providers = new java.util.HashMap<String, ProviderEvidence>();
                    while (rs.next()) {
                        providers.put(rs.getString("stage"),
                                rs.getBoolean("codex") ? ProviderEvidence.CODEX : ProviderEvidence.RECORDED);
                    }
                    return providers;
                }, pr.scope().installationId(), pr.scope().repositoryId(), pr.number(),
                job.headSha(), job.version(), job.version());
    }

    @Override
    public void skip(Job job) {
        jdbc.update("""
                UPDATE author_notifications SET status = 'SKIPPED'
                WHERE id = ? AND status = 'PENDING' AND attempts = ? AND available_at > clock_timestamp()
                """, job.id(), job.attempt());
    }

    private RuntimeException sanitized(RuntimeException failure) {
        if (failure instanceof AuthorizationDenied) return new AuthorizationDenied();
        if (failure instanceof AuthorizationUnavailable) return new AuthorizationUnavailable();
        return new IllegalStateException("Author notification send unavailable");
    }
}
