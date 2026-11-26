package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequest;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewProvider;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SandboxCodexTriggerTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final PullRequestId PR = new PullRequestId(new RepositoryScope(101, 1001), 35);
    private static final String HEAD = "a".repeat(40);
    private final ReviewRequest request = new ReviewRequest(PR, HEAD, "BASELINE", "codex",
            ReviewRequest.Trigger.SANDBOX_OPERATOR, 42, false, 1);
    private final GitHubSandboxUserCredentials credentials = mock(GitHubSandboxUserCredentials.class);
    private final ReviewProvider provider = mock(ReviewProvider.class);
    private final SandboxCodexTrigger trigger = new SandboxCodexTrigger(request, credentials, provider,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final StringWriter output = new StringWriter();
    private final PrintWriter terminal = new PrintWriter(output);

    @Test void verifiedConsentDispatchesTheExactTargetOnceAndClosesTheGrant() throws InterruptedException {
        challenge(NOW.plusSeconds(60));
        when(credentials.poll(any())).thenReturn(new GitHubSandboxDeviceOAuth.Progress(null,
                new GitHubSandboxDeviceOAuth.Result(42, PR, HEAD)));
        when(provider.request(request)).thenReturn(ReviewProvider.Result.COMMENT_CREATED);

        assertEquals(ReviewProvider.Result.COMMENT_CREATED, trigger.run(terminal));
        assertTrue(output.toString().contains("https://github.com/login/device"));
        assertTrue(output.toString().contains("ABCD-1234"));
        var session = ArgumentCaptor.forClass(UUID.class);
        var order = inOrder(credentials, provider);
        order.verify(credentials).start(session.capture());
        order.verify(credentials).poll(session.getValue());
        order.verify(provider).request(request);
        order.verify(credentials).close();
        order.verifyNoMoreInteractions();
    }

    @Test void expiredChallengeNeverPollsOrDispatches() {
        challenge(NOW);
        assertThrows(ReviewRequestFailure.class, () -> trigger.run(terminal));
        verify(credentials, never()).poll(any());
        verify(credentials).close();
        verifyNoInteractions(provider);
    }

    @Test void nonmatchingHeadNeverReachesTheProvider() {
        challenge(NOW.plusSeconds(60));
        when(credentials.poll(any())).thenReturn(new GitHubSandboxDeviceOAuth.Progress(null,
                new GitHubSandboxDeviceOAuth.Result(42, PR, "b".repeat(40))));
        var denied = assertThrows(ReviewRequestFailure.class, () -> trigger.run(terminal));
        assertEquals(ReviewRequestFailure.Code.STALE_HEAD, denied.code());
        verify(credentials).close();
        verifyNoInteractions(provider);
    }

    @Test void policyDenialClearsTheGrantAndNeverTriggersAnotherAttempt() {
        challenge(NOW.plusSeconds(60));
        when(credentials.poll(any())).thenReturn(new GitHubSandboxDeviceOAuth.Progress(null,
                new GitHubSandboxDeviceOAuth.Result(42, PR, HEAD)));
        when(provider.request(request)).thenThrow(new ReviewRequestFailure(ReviewRequestFailure.Code.POLICY_DENIED));

        var denied = assertThrows(ReviewRequestFailure.class, () -> trigger.run(terminal));
        assertEquals(ReviewRequestFailure.Code.POLICY_DENIED, denied.code());
        verify(provider).request(request);
        verify(credentials).close();
    }

    private void challenge(Instant expiry) {
        when(credentials.start(any())).thenReturn(new GitHubSandboxDeviceOAuth.Challenge(
                "ABCD-1234", URI.create("https://github.com/login/device"), expiry));
    }
}
