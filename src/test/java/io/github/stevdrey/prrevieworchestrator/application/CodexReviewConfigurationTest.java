package io.github.stevdrey.prrevieworchestrator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import io.github.stevdrey.prrevieworchestrator.config.CodexReviewConfiguration;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CodexReviewConfigurationTest {
    final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(CodexReviewConfiguration.class);
    @Test void defaultDisabledIsActionableAndNeedsNoCredentialsOrDatabase() {
        context.run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(ReviewProvider.class);
            var failure = assertThrows(ReviewRequestFailure.class, () -> app.getBean(ReviewProvider.class).request(null));
            assertEquals(ReviewRequestFailure.Code.DISABLED, failure.code());
        });
    }
    @Test void enabledWithoutApprovedPortsFailsStartup() {
        context.withPropertyValues("orchestrator.codex.enabled=true", "orchestrator.codex.actor-id=42")
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(RepositoryAuthorization.class, () -> mock(RepositoryAuthorization.class))
                .run(app -> assertThat(app).hasFailed());
    }
}
