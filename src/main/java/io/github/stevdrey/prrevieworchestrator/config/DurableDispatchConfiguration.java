package io.github.stevdrey.prrevieworchestrator.config;

import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcWorkQueue;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.PostgresDeadlines;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

/** No default planner or external command adapter: enabling without approved adapters fails startup. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "orchestrator.dispatch", name = "enabled", havingValue = "true")
@EnableScheduling
public class DurableDispatchConfiguration {
    @Bean
    WorkQueue workQueue(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        PostgresDeadlines.requireTransactionTimeout(jdbc, "durable dispatch");
        // 15/30/60/120s retry delays: ~3.75 minutes for transient outages before the 5-attempt ceiling.
        return new JdbcWorkQueue(jdbc, manager,
                new Limits(Duration.ofSeconds(30), Duration.ofSeconds(15), Duration.ofMinutes(5)));
    }

    @Bean
    DurableDispatcher durableDispatcher(WorkQueue queue, RepositoryAuthorization authorization,
            EventPlanner planner, CommandGateway gateway) {
        return new DurableDispatcher(queue, authorization, planner, gateway);
    }

    /** Separate threads so a command holding its bounded transaction never stalls inbox planning. */
    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("durable-dispatch-");
        return scheduler;
    }

    @Bean
    Ticks durableDispatchTicks(DurableDispatcher dispatcher) { return new Ticks(dispatcher); }

    public static final class Ticks {
        private final DurableDispatcher dispatcher;
        Ticks(DurableDispatcher dispatcher) { this.dispatcher = dispatcher; }

        /** Drain up to this many items per queue per tick; bounded pressure without one-per-second latency. */
        static final int MAX_PER_TICK = 16;

        @Scheduled(fixedDelay = 1000)
        public void inboxTick() { drain(dispatcher::processEvent); }

        @Scheduled(fixedDelay = 1000)
        public void outboxTick() { drain(dispatcher::processCommand); }

        private void drain(java.util.function.BooleanSupplier step) {
            // Stop at an empty queue or the first failure (one WARN per tick); never leak driver diagnostics.
            try {
                for (int i = 0; i < MAX_PER_TICK && step.getAsBoolean(); i++) { }
            } catch (RuntimeException ignored) {
                unavailable();
            }
        }

        private void unavailable() {
            LoggerFactory.getLogger(Ticks.class).warn("Durable dispatch unavailable; work remains recoverable");
        }
    }
}
