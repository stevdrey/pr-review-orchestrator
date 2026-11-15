package io.github.stevdrey.prrevieworchestrator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.stevdrey.prrevieworchestrator.config.DurableDispatchConfiguration;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.*;
import io.github.stevdrey.prrevieworchestrator.ports.WorkQueue;
import java.time.Duration;
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
