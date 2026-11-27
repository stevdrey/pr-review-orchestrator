package io.github.stevdrey.prrevieworchestrator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.stevdrey.prrevieworchestrator.adapters.github.GitHubAppConfiguration;
import io.github.stevdrey.prrevieworchestrator.adapters.github.GitHubReviewNormalizer;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcFixCorrelationStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcReviewEvidenceStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcReviewStateStore;
import io.github.stevdrey.prrevieworchestrator.config.DurableDispatchConfiguration;
import io.github.stevdrey.prrevieworchestrator.config.RepositoryAuthorizationConfiguration.Allowlist;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.*;
import io.github.stevdrey.prrevieworchestrator.ports.CommandGateway;
import io.github.stevdrey.prrevieworchestrator.ports.InstallationAuthorization;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewIntentPolicy;
import io.github.stevdrey.prrevieworchestrator.ports.WorkQueue;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class DurableDispatchConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DurableDispatchConfiguration.class);

    @Test
    void disabledWorkerNeedsNeitherAdaptersNorDatabase() {
        context.run(app -> assertThat(app).hasNotFailed().doesNotHaveBean(WorkQueue.class)
                .doesNotHaveBean(DurableDispatcher.class).doesNotHaveBean(DurableDispatchConfiguration.Ticks.class));
    }

    @Test
    void enabledWorkerWithoutApprovedAdaptersFailsStartup() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT current_setting('transaction_timeout', true)", String.class)).thenReturn("0");
        context.withPropertyValues("orchestrator.dispatch.enabled=true")
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(RepositoryAuthorization.class, () -> mock(RepositoryAuthorization.class))
                .run(app -> assertThat(app).hasFailed());
    }

    @Test
    void synchronizePlanningStartsWithoutOutboundAdaptersAndRejectsLiveDispatch() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT current_setting('transaction_timeout', true)", String.class)).thenReturn("0");
        var sandbox = context.withPropertyValues("orchestrator.dispatch.enabled=true",
                        "orchestrator.sandbox-ci.enabled=true", "orchestrator.sandbox-ci.synchronize-enabled=true",
                        "orchestrator.sandbox-ci.installation-id=101", "orchestrator.sandbox-ci.read-installation-id=102",
                        "orchestrator.sandbox-ci.repository-id=1001", "orchestrator.sandbox-ci.repository=fixture/repo",
                        "orchestrator.sandbox-ci.pr-number=7", "orchestrator.sandbox-ci.head=" + "a".repeat(40),
                        "orchestrator.sandbox-ci.workflow-ids[0]=21")
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(JdbcReviewStateStore.class, () -> mock(JdbcReviewStateStore.class))
                .withBean(JdbcReviewEvidenceStore.class, () -> mock(JdbcReviewEvidenceStore.class))
                .withBean(JdbcFixCorrelationStore.class, () -> mock(JdbcFixCorrelationStore.class))
                .withBean(GitHubReviewNormalizer.class, () -> mock(GitHubReviewNormalizer.class))
                .withBean(ReviewLifecycle.class, () -> mock(ReviewLifecycle.class))
                .withBean(RepositoryAuthorization.class, () -> mock(RepositoryAuthorization.class))
                .withBean(InstallationAuthorization.class, () -> mock(InstallationAuthorization.class))
                .withBean(Allowlist.class, () -> new Allowlist(Map.of(101L, Set.of(1001L))))
                .withBean(GitHubAppConfiguration.Settings.class,
                        () -> new GitHubAppConfiguration.Settings("Iv1.fixture", Path.of("unused-fixture-key")));

        sandbox.run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(DurableDispatcher.class)
                    .doesNotHaveBean(ReviewIntentPolicy.class).doesNotHaveBean(CommandGateway.class);
            assertFalse(app.getBean(DurableDispatcher.class).processCommand());
        });
        sandbox.withPropertyValues("orchestrator.codex.enabled=true").run(app -> assertThat(app).hasFailed());
        sandbox.withPropertyValues("orchestrator.notifications.enabled=true").run(app -> assertThat(app).hasFailed());
    }

    @Test
    void metadataKeysAndRetryLimitsAreDeterministicAndBounded() {
        var intent = new Intent(7, "a".repeat(40), "BASELINE", "fixture", "REQUEST_REVIEW");
        var scope = new io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope(101, 1001);
        assertEquals(intent.key(scope), new Intent(7, "a".repeat(40), "BASELINE", "fixture", "REQUEST_REVIEW").key(scope));
        assertNotEquals(intent.key(scope), new Intent(7, "a".repeat(40), "BASELINE", "fixture", "NOTIFY").key(scope));
        assertThrows(IllegalArgumentException.class, () -> new Intent(7, "a".repeat(40), "BASELINE", "fixture\nsecret", "REQUEST_REVIEW"));
        assertThrows(IllegalArgumentException.class, () -> new Limits(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(8)));
        var limits = new Limits(Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofMinutes(5));
        assertEquals(Duration.ofMinutes(5), limits.backoff(Integer.MAX_VALUE));
    }
}
