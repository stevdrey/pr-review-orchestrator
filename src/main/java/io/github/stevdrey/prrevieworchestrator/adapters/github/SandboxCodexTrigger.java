package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequest;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewProvider;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.ACTOR_DENIED;
import static io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure.Code.STALE_HEAD;

/** Explicit, one-shot operator entry point for an already open, pinned sandbox PR. */
public final class SandboxCodexTrigger {
    private final ReviewRequest target;
    private final GitHubSandboxUserCredentials credentials;
    private final ReviewProvider provider;
    private final Clock clock;

    public SandboxCodexTrigger(ReviewRequest target, GitHubSandboxUserCredentials credentials,
            ReviewProvider provider, Clock clock) {
        if (target.trigger() != ReviewRequest.Trigger.SANDBOX_OPERATOR || target.bot()) {
            throw new IllegalArgumentException("A human sandbox operator trigger is required");
        }
        this.target = target;
        this.credentials = credentials;
        this.provider = provider;
        this.clock = clock;
    }

    public ReviewProvider.Result run(PrintWriter terminal) throws InterruptedException {
        try (var grant = credentials) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            var session = UUID.randomUUID();
            var challenge = grant.start(session);
            terminal.printf("Authorize GitHub Device Flow at %s with code %s (expires %s).%n",
                    challenge.verificationUrl(), challenge.userCode(), challenge.expiresAt());
            terminal.flush();

            while (!Thread.currentThread().isInterrupted() && clock.instant().isBefore(challenge.expiresAt())) {
                var progress = grant.poll(session);
                if (progress.verified() != null) {
                    var verified = progress.verified();
                    if (verified.actorId() != target.senderId() || !target.pr().equals(verified.pr())) {
                        throw new ReviewRequestFailure(ACTOR_DENIED);
                    }
                    if (!target.headSha().equals(verified.head())) throw new ReviewRequestFailure(STALE_HEAD);
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    return provider.request(target);
                }
                var wakeAt = progress.nextPoll();
                if (!wakeAt.isBefore(challenge.expiresAt())) break;
                var wait = Duration.between(clock.instant(), wakeAt);
                if (wait.isPositive()) Thread.sleep(wait);
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            throw new ReviewRequestFailure(ACTOR_DENIED);
        }
    }
}
