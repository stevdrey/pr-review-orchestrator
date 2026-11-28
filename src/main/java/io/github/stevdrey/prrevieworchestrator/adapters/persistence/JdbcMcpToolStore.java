package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationDenied;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition;
import io.github.stevdrey.prrevieworchestrator.ports.McpToolStore;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "orchestrator.mcp", name = "enabled", havingValue = "true")
public class JdbcMcpToolStore implements McpToolStore {
    private final JdbcTemplate jdbc;
    private final JdbcReviewStateStore states;
    private final RepositoryAuthorization authorization;

    public JdbcMcpToolStore(JdbcTemplate jdbc, JdbcReviewStateStore states, RepositoryAuthorization authorization) {
        this.jdbc = jdbc;
        this.states = states;
        this.authorization = authorization;
    }

    @Override
    public void authenticate(String tokenDigest) {
        states.transaction(() -> {}, () -> {
            authorizedSession(tokenDigest);
            requireUnexpired(tokenDigest);
            return null;
        });
    }

    @Override
    public <T> T execute(String tokenDigest, PullRequestId pr, Function<Access, T> operation) {
        return states.transaction(() -> {}, () -> {
            Session session = authorizedSession(tokenDigest);
            if (!session.pr().equals(pr)) throw new AuthorizationDenied();
            PostgresLocks.lockPr(jdbc, pr);
            requireUnexpired(tokenDigest);
            T result = operation.apply(new LockedAccess(session));
            requireUnexpired(tokenDigest);
            return result;
        });
    }

    private Session authorizedSession(String digest) {
        var sessions = jdbc.query("""
                SELECT actor_id, installation_id, repository_id, pr_number, approved_tool, command_id,
                    approved_head_sha, approved_version
                FROM mcp_tool_sessions
                WHERE token_sha256 = ? AND revoked_at IS NULL
                    AND issued_at <= clock_timestamp() AND expires_at > clock_timestamp()
                FOR SHARE
                """, (rs, row) -> {
                    var pr = new PullRequestId(new RepositoryScope(rs.getLong("installation_id"),
                            rs.getLong("repository_id")), rs.getInt("pr_number"));
                    var command = rs.getObject("command_id", UUID.class);
                    var approval = command == null ? null : new Approval(rs.getString("approved_tool"),
                            command, rs.getString("approved_head_sha"), rs.getLong("approved_version"));
                    return new Session(rs.getLong("actor_id"), pr, approval);
                }, digest);
        Session session = sessions.stream().findFirst().orElseThrow(AuthorizationDenied::new);
        authorization.requireAuthorized(session.pr().scope());
        return session;
    }

    private void requireUnexpired(String digest) {
        // Lock waits can outlive the grant. Recheck with the database wall clock before exposing data or acting.
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT expires_at > clock_timestamp() FROM mcp_tool_sessions WHERE token_sha256 = ?
                """, Boolean.class, digest))) throw new AuthorizationDenied();
    }

    private final class LockedAccess implements Access {
        private final Session session;

        private LockedAccess(Session session) {
            this.session = session;
        }

        @Override
        public Session session() { return session; }

        @Override
        public PullRequestReview current() {
            return states.load(session.pr(), false)
                    .orElseThrow(() -> new ReviewConflict(ReviewConflict.Code.NOT_FOUND));
        }

        @Override
        public List<Finding> findings(String headSha, UUID after, int limit) {
            var pr = session.pr();
            long installation = pr.scope().installationId();
            long repository = pr.scope().repositoryId();
            // The clean gate's effective projection across every round of the PR: retained findings from earlier
            // heads stay visible, and superseded inline copies collapse to their current occurrence.
            return jdbc.query(JdbcGeneralFeedbackStore.LAST_HEAD + ", "
                    + JdbcGeneralFeedbackStore.EFFECTIVE_INLINE_FINDING_CTES + """
                    , inline_rows AS (
                        SELECT DISTINCT ON (coalesce(stable_finding_id, id))
                            id, stable_finding_id, source_kind, severity, status, thread_resolved
                        FROM effective_inline_findings
                        ORDER BY coalesce(stable_finding_id, id), at_round DESC NULLS LAST,
                            linked_at DESC NULLS LAST, id DESC
                    ), general_effective AS (
                    """ + JdbcGeneralFeedbackStore.EFFECTIVE_GENERAL_FINDINGS + """
                    ), general_status AS (
                        SELECT stable_finding_id,
                            CASE WHEN bool_and(decision IS NOT DISTINCT FROM 'DISMISSED') THEN 'DISMISSED'
                                 WHEN bool_and(decision IS NOT NULL) THEN 'ACKNOWLEDGED'
                                 ELSE 'OPEN' END AS status
                        FROM general_effective GROUP BY stable_finding_id
                    ), general_rows AS (
                        SELECT DISTINCT ON (e.stable_finding_id)
                            e.id, e.stable_finding_id, e.source_kind, e.severity, g.status, e.thread_resolved
                        FROM general_effective e JOIN general_status g USING (stable_finding_id)
                        ORDER BY e.stable_finding_id, e.id
                    ), legacy_rows AS (
                        SELECT f.id, f.stable_finding_id, f.source_kind, f.severity, f.status, f.thread_resolved
                        FROM findings f JOIN review_cycles c ON c.id = f.review_cycle_id
                        WHERE c.installation_id = ? AND c.repository_id = ? AND c.pr_number = ?
                            AND f.source_kind IN ('REVIEW', 'ISSUE_COMMENT') AND f.stable_finding_id IS NULL
                    )
                    SELECT * FROM (
                        SELECT * FROM inline_rows UNION ALL SELECT * FROM general_rows
                        UNION ALL SELECT * FROM legacy_rows
                    ) effective
                    WHERE (CAST(? AS uuid) IS NULL OR id > ?)
                    ORDER BY id LIMIT ?
                    """, (rs, row) -> new Finding(rs.getObject("id", UUID.class),
                    rs.getObject("stable_finding_id", UUID.class), rs.getString("source_kind"),
                    rs.getString("severity"), rs.getString("status"), rs.getObject("thread_resolved", Boolean.class)),
                    installation, repository, pr.number(),
                    installation, repository, pr.number(),
                    headSha, installation, repository, pr.number(),
                    installation, repository, pr.number(),
                    after, after, limit);
        }

        @Override
        public boolean pause(ReviewTransition transition) {
            try {
                states.applyLocked(session.pr(), transition, null);
                return true;
            } catch (ReviewConflict conflict) {
                if (conflict.code() != ReviewConflict.Code.DUPLICATE_TRANSITION) throw conflict;
                // A journal match alone could be a pause from a different ingress. Require our committed audit too.
                var pr = session.pr();
                boolean appliedHere = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM audit_entries WHERE correlation_id = ?
                            AND installation_id = ? AND repository_id = ? AND head_sha = ?
                            AND actor = ? AND action = 'mcp.pause_cycle' AND outcome = 'APPLIED' AND evidence_ref = ?)
                        """, Boolean.class, transition.id(), pr.scope().installationId(), pr.scope().repositoryId(),
                        transition.expectedHeadSha(), actor(), evidenceRef()));
                if (!appliedHere) throw new ReviewConflict(ReviewConflict.Code.TRANSITION_ID_REUSED);
                return false;
            }
        }

        @Override
        public void audit(UUID correlationId, String tool, String headSha, String outcome) {
            var pr = session.pr();
            jdbc.update("""
                    INSERT INTO audit_entries (id, installation_id, repository_id, head_sha, correlation_id,
                        actor, action, outcome, evidence_ref)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), pr.scope().installationId(), pr.scope().repositoryId(), headSha,
                    correlationId, actor(), "mcp." + tool, outcome, evidenceRef());
        }

        private String actor() { return "mcp-user:" + session.actorId(); }
        private String evidenceRef() { return "pr:" + session.pr().number(); }
    }
}
