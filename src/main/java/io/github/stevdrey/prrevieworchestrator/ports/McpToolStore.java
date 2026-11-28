package io.github.stevdrey.prrevieworchestrator.ports;

import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** A bounded transaction with live session and installation authorization and, for tool calls, a PR lock. */
public interface McpToolStore {
    record Approval(String tool, UUID commandId, String headSha, long version) {}
    record Session(long actorId, PullRequestId pr, Approval approval) {}
    record Finding(UUID id, UUID stableId, String sourceKind, String severity, String status, Boolean threadResolved) {}

    void authenticate(String tokenDigest);
    <T> T execute(String tokenDigest, PullRequestId pr, Function<Access, T> operation);

    interface Access {
        Session session();
        PullRequestReview current();
        List<Finding> findings(String headSha, UUID after, int limit);
        boolean pause(ReviewTransition transition);
        void audit(UUID correlationId, String tool, String headSha, String outcome);
    }
}
