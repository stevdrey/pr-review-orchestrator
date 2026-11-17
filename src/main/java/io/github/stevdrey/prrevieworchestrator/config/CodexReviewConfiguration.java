package io.github.stevdrey.prrevieworchestrator.config;

import io.github.stevdrey.prrevieworchestrator.adapters.github.*;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcReviewRequestStore;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure;
import io.github.stevdrey.prrevieworchestrator.ports.*;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class CodexReviewConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.codex", name = "enabled", havingValue = "false", matchIfMissing = true)
    ReviewProvider disabledCodexReviewProvider() {
        return request -> { throw new ReviewRequestFailure(ReviewRequestFailure.Code.DISABLED); };
    }
    /** No credential or accounting/budget implementations ship. Enabling requires approved beans. */
    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.codex", name = "enabled", havingValue = "true")
    ReviewProvider codexReviewProvider(JdbcTemplate jdbc, PlatformTransactionManager manager,
            RepositoryAuthorization authorization, CodexApproval approval, CodexUserCredentials credentials,
            @Value("${orchestrator.codex.actor-id}") long actorId,
            @Value("${orchestrator.codex.max-requests:6}") int limit) {
        return new CodexReviewAdapter(authorization, new JdbcReviewRequestStore(jdbc, manager), approval,
                new GitHubCodexComments(credentials, Clock.systemUTC()), actorId, limit);
    }
}
