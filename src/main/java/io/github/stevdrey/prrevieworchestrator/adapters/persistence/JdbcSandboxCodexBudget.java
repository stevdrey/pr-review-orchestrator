package io.github.stevdrey.prrevieworchestrator.adapters.persistence;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequest;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.POLICY_DENIED;

/** Local hard limits and conservative holds for an isolated sandbox database. */
public final class JdbcSandboxCodexBudget {
    /** Supplied by a trusted deployment using a provider-verified upper bound and matching units. */
    public record Terms(String usageUnit, long maxPerRequest, long perPr, long perInstallation, long global) {
        public Terms {
            if (usageUnit == null || !usageUnit.matches("[a-z][a-z0-9_-]{0,31}")
                    || maxPerRequest <= 0 || perPr <= 0 || perInstallation <= 0 || global <= 0) {
                throw new IllegalArgumentException("Invalid sandbox budget terms");
            }
        }
    }

    private final JdbcTemplate jdbc;
    private final Terms terms;

    public JdbcSandboxCodexBudget(JdbcTemplate jdbc, Terms terms) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.terms = java.util.Objects.requireNonNull(terms);
    }

    // Only called after the corresponding review_request INSERT in the store's transaction.
    void reserve(ReviewRequest request) {
        var pr = request.pr();
        var scope = pr.scope();
        // All reservations acquire these rows in the same order. A later denial rolls back
        // every earlier update together with the request.
        hold("global", terms.global());
        hold("installation:" + scope.installationId(), terms.perInstallation());
        hold("pr:" + scope.installationId() + ":" + scope.repositoryId() + ":" + pr.number(), terms.perPr());
        jdbc.update("""
                INSERT INTO sandbox_codex_budget_reservations (request_key, usage_unit, units)
                VALUES (?, ?, ?)
                """, request.key(), terms.usageUnit(), terms.maxPerRequest());
    }

    void requireHeld(ReviewRequest request) {
        boolean held = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM sandbox_codex_budget_reservations
                    WHERE request_key = ? AND usage_unit = ? AND units = ?)
                """, Boolean.class, request.key(), terms.usageUnit(), terms.maxPerRequest()));
        if (!held) throw new ReviewRequestFailure(POLICY_DENIED);
    }

    private void hold(String key, long capacity) {
        jdbc.update("""
                INSERT INTO sandbox_codex_budget_limits (scope_key, usage_unit, capacity, reserved)
                VALUES (?, ?, ?, 0) ON CONFLICT (scope_key) DO NOTHING
                """, key, terms.usageUnit(), capacity);
        int changed = jdbc.update("""
                UPDATE sandbox_codex_budget_limits SET reserved = reserved + ?
                WHERE scope_key = ? AND usage_unit = ? AND capacity = ? AND capacity - reserved >= ?
                """, terms.maxPerRequest(), key, terms.usageUnit(), capacity, terms.maxPerRequest());
        if (changed != 1) throw new ReviewRequestFailure(POLICY_DENIED);
    }
}
