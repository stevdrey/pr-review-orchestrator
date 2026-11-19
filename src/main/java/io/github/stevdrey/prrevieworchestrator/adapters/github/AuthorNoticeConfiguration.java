package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcAuthorNoticeStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcReviewStateStore;
import io.github.stevdrey.prrevieworchestrator.application.NotifyAuthor;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import java.time.Clock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
public class AuthorNoticeConfiguration {
    @Bean
    GitHubReviewNormalizer gitHubReviewNormalizer(
            @Value("${orchestrator.notifications.bot-login:}") String botLogin) {
        return new GitHubReviewNormalizer(botLogin);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "orchestrator.notifications", name = "enabled", havingValue = "true")
    @EnableScheduling
    static class Delivery {
        @Bean
        Ticks authorNotificationTicks(JdbcTemplate jdbc, JdbcReviewStateStore states, GitHubInstallationTokens tokens,
                RepositoryAuthorization authorization, @Value("${orchestrator.notifications.bot-login:}") String botLogin) {
            if (!botLogin.matches("[a-zA-Z0-9-]{1,100}\\[bot\\]")) {
                throw new IllegalArgumentException("A GitHub App bot login is required for author notifications");
            }
            return new Ticks(new NotifyAuthor(new JdbcAuthorNoticeStore(jdbc, states),
                    new GitHubAuthorNotices(tokens, Clock.systemUTC(), botLogin), authorization));
        }
    }

    static final class Ticks {
        private final NotifyAuthor notifyAuthor;

        Ticks(NotifyAuthor notifyAuthor) {
            this.notifyAuthor = notifyAuthor;
        }

        @Scheduled(fixedDelay = 1000)
        void tick() {
            try {
                notifyAuthor.processNext();
            } catch (RuntimeException ignored) {
                LoggerFactory.getLogger(Ticks.class).warn("Author notification failed; see author_notifications status");
            }
        }
    }
}
