package io.github.stevdrey.prrevieworchestrator.config;

import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcWorkQueue;
import io.github.stevdrey.prrevieworchestrator.application.DurableDispatcher;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Limits;
import io.github.stevdrey.prrevieworchestrator.ports.*;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

/** No default planner or external command adapter: enabling without approved adapters fails startup. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "orchestrator.dispatch", name = "enabled", havingValue = "true")
@EnableScheduling
public class DurableDispatchConfiguration {
    @Bean
    WorkQueue workQueue(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        if (jdbc.queryForObject("SELECT current_setting('transaction_timeout', true)", String.class) == null) {
            throw new IllegalStateException("PostgreSQL 17+ is required for durable dispatch");
        }
        return new JdbcWorkQueue(jdbc, manager,
                new Limits(Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofMinutes(5)));
    }

    @Bean
    DurableDispatcher durableDispatcher(WorkQueue queue, RepositoryAuthorization authorization,
            EventPlanner planner, CommandGateway gateway) {
        return new DurableDispatcher(queue, authorization, planner, gateway);
    }

    @Bean
    Ticks durableDispatchTicks(DurableDispatcher dispatcher) { return new Ticks(dispatcher); }

    public static final class Ticks {
        private final DurableDispatcher dispatcher;
        Ticks(DurableDispatcher dispatcher) { this.dispatcher = dispatcher; }

        @Scheduled(fixedDelay = 1000)
        public void tick() {
            // One item per queue per tick bounds pressure; DB failure must never leak driver diagnostics.
            try { dispatcher.processEvent(); } catch (RuntimeException ignored) { unavailable(); }
            try { dispatcher.processCommand(); } catch (RuntimeException ignored) { unavailable(); }
        }

        private void unavailable() {
            LoggerFactory.getLogger(Ticks.class).warn("Durable dispatch unavailable; work remains recoverable");
        }
    }
}
