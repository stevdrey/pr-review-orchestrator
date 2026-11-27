package io.github.stevdrey.prrevieworchestrator.adapters.events;

import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcDomainEventQueue;
import io.github.stevdrey.prrevieworchestrator.application.DeliverDomainEvents;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "orchestrator.domain-events", name = "delivery-enabled", havingValue = "true")
@EnableConfigurationProperties(DomainEventDeliveryConfiguration.Properties.class)
@EnableScheduling
class DomainEventDeliveryConfiguration {
    @Bean
    SignedDomainEventWebhooks domainEventPublisher(Properties properties) {
        return new SignedDomainEventWebhooks(properties.callbacks() == null ? Map.of() : properties.callbacks(),
                properties.signingKeyDirectory());
    }

    @Bean
    Ticks domainEventTicks(JdbcTemplate jdbc, PlatformTransactionManager manager,
            RepositoryAuthorization authorization, SignedDomainEventWebhooks publisher) {
        return new Ticks(new DeliverDomainEvents(new JdbcDomainEventQueue(jdbc, manager), authorization, publisher));
    }

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler domainEventScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("domain-event-delivery-");
        return scheduler;
    }

    @ConfigurationProperties(prefix = "orchestrator.domain-events")
    record Properties(Map<UUID, URI> callbacks, Path signingKeyDirectory) {}

    record Ticks(DeliverDomainEvents worker) {
        @Scheduled(fixedDelay = 1000, scheduler = "domainEventScheduler")
        void tick() {
            try {
                worker.processNext();
            } catch (RuntimeException ignored) {
                LoggerFactory.getLogger(Ticks.class).warn("PR domain event delivery failed; see pr_event_deliveries");
            }
        }
    }
}
