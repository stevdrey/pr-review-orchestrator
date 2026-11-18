package io.github.stevdrey.prrevieworchestrator.domain;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Ci;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** An authoritative read of configured dependencies for one PR and commit. */
public record CiEvidence(UUID id, PullRequestId pr, String headSha, Set<Dependency> required,
        List<Run> runs, boolean complete) {
    public sealed interface Dependency permits Check, Workflow {}

    public record Check(long appId, String name) implements Dependency {
        public Check {
            if (appId <= 0 || name == null || name.isBlank() || name.length() > 1024) {
                throw new IllegalArgumentException("Invalid required check");
            }
        }
    }

    public record Workflow(long workflowId) implements Dependency {
        public Workflow {
            if (workflowId <= 0) throw new IllegalArgumentException("Invalid required workflow");
        }
    }

    public record Run(Dependency dependency, String headSha, Ci status) {
        public Run {
            Objects.requireNonNull(dependency);
            CommitSha.require(headSha);
            Objects.requireNonNull(status);
        }
    }

    public CiEvidence {
        Objects.requireNonNull(id);
        Objects.requireNonNull(pr);
        CommitSha.require(headSha);
        required = Set.copyOf(required);
        runs = List.copyOf(runs);
    }

    public Ci status() {
        if (!complete || required.isEmpty()) return Ci.UNKNOWN;
        var current = new HashMap<Dependency, Ci>();
        for (Run run : runs) {
            if (headSha.equals(run.headSha()) && required.contains(run.dependency())) {
                // Multiple purported current runs are ambiguous, even if both report success.
                current.merge(run.dependency(), run.status(), (first, duplicate) -> Ci.UNKNOWN);
            }
        }
        if (current.containsValue(Ci.FAILED)) return Ci.FAILED;
        if (current.size() != required.size() || current.containsValue(Ci.UNKNOWN)) return Ci.UNKNOWN;
        return current.containsValue(Ci.PENDING) ? Ci.PENDING : Ci.PASSED;
    }
}
