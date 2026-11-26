package io.github.stevdrey.prrevieworchestrator.config;

import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcWorkQueue;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcFixCorrelationStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcReviewStateStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcReviewEvidenceStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcSandboxCiInbox;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcSandboxReviewInbox;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcSandboxReviewPlanning;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.PostgresDeadlines;
import io.github.stevdrey.prrevieworchestrator.adapters.github.GitHubAppConfiguration;
import io.github.stevdrey.prrevieworchestrator.adapters.github.GitHubAppJwt;
import io.github.stevdrey.prrevieworchestrator.adapters.github.GitHubReviewNormalizer;
import io.github.stevdrey.prrevieworchestrator.adapters.github.GitHubSandboxCiReader;
import io.github.stevdrey.prrevieworchestrator.adapters.github.GitHubSandboxReviewReader;
import io.github.stevdrey.prrevieworchestrator.application.DurableDispatcher;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.application.ReviewLifecycle;
import io.github.stevdrey.prrevieworchestrator.application.SandboxCiReconciler;
import io.github.stevdrey.prrevieworchestrator.application.SandboxReviewReconciler;
import io.github.stevdrey.prrevieworchestrator.application.SandboxReviewPlanning;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Event;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork.Limits;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.ports.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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
    JdbcWorkQueue workQueue(JdbcTemplate jdbc, PlatformTransactionManager manager, Environment env) {
        PostgresDeadlines.requireTransactionTimeout(jdbc, "durable dispatch");
        boolean reviewPlanning = env.getProperty("orchestrator.sandbox-ci.enabled", Boolean.class, false)
                && env.getProperty("orchestrator.sandbox-ci.synchronize-enabled", Boolean.class, false);
        // A planning claim spans separate bounded PR, Git object and CI reads.
        var lease = Duration.ofSeconds(reviewPlanning ? 90 : 30);
        // 15/30/60/120s retry delays: ~3.75 minutes for transient outages before the 5-attempt ceiling.
        return new JdbcWorkQueue(jdbc, manager, new Limits(lease, Duration.ofSeconds(15), Duration.ofMinutes(5)));
    }

    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.sandbox-ci", name = "enabled", havingValue = "false", matchIfMissing = true)
    DurableDispatcher durableDispatcher(WorkQueue queue, RepositoryAuthorization authorization,
            EventPlanner planner, CommandGateway gateway) {
        return new DurableDispatcher(queue, authorization, planner, gateway);
    }

    @ConfigurationProperties("orchestrator.sandbox-ci")
    record SandboxSettings(long installationId, long readInstallationId, long repositoryId,
            String repository, int prNumber, String head, Set<Long> workflowIds, boolean reviewEvidenceEnabled,
            boolean synchronizeEnabled) {
        SandboxCiReconciler.Policy policy() {
            var scope = new RepositoryScope(installationId, repositoryId);
            var readScope = new RepositoryScope(readInstallationId, repositoryId);
            return new SandboxCiReconciler.Policy(new PullRequestId(scope, prNumber), readScope, repository, head, workflowIds);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "orchestrator", name = {"dispatch.enabled", "sandbox-ci.enabled"}, havingValue = "true")
    @EnableConfigurationProperties(SandboxSettings.class)
    static class SandboxConfiguration {
        @Bean
        DurableDispatcher sandboxCiDispatcher(JdbcWorkQueue queue, JdbcTemplate jdbc, JdbcReviewStateStore states,
                JdbcReviewEvidenceStore evidenceStore, JdbcFixCorrelationStore fixes, GitHubReviewNormalizer normalizer,
                ReviewLifecycle lifecycle, RepositoryAuthorization ingressAuthorization, InstallationAuthorization installations,
                RepositoryAuthorizationConfiguration.Allowlist allowlist, SandboxSettings settings,
                GitHubAppConfiguration.Settings github, Environment env, ObjectProvider<ReviewIntentPolicy> reviewPolicy,
                @Value("${orchestrator.codex.max-requests:6}") int reviewLimit) {
            var policy = settings.policy();
            var ingress = policy.pr().scope();
            if (env.getProperty("orchestrator.codex.enabled", Boolean.class, false)
                    || env.getProperty("orchestrator.notifications.enabled", Boolean.class, false)
                    || !Map.of(ingress.installationId(), Set.of(ingress.repositoryId())).equals(allowlist.repositories())
                    || github.privateKeyFile() == null) {
                throw new IllegalStateException("Sandbox CI requires one ingress scope, a read key and disabled outbound effects");
            }
            var readAuthorization = new RepositoryAuthorization(Set.of(policy.readScope()), installations);
            var clock = Clock.systemUTC();
            var jwt = new GitHubAppJwt(github.clientId(), github.privateKeyFile(), clock);
            var reader = new GitHubSandboxCiReader(readAuthorization, jwt, clock);
            var inbox = new JdbcSandboxCiInbox(jdbc, queue, states);
            var reconciler = new SandboxCiReconciler(policy, queue, ingressAuthorization, lifecycle, reader,
                    (event, expected, evidence, authorize) -> inbox.reconcile(event, expected, evidence, () -> {
                        authorize.run();
                        readAuthorization.requireAuthorized(policy.readScope());
                    }));
            Predicate<Event> reconcile = reconciler::complete;
            if (settings.synchronizeEnabled()) {
                var planningInbox = new JdbcSandboxReviewPlanning(jdbc, queue, states, fixes,
                        reviewPolicy.getIfAvailable(() -> (pr, intent) -> false), reviewLimit);
                var planning = new SandboxReviewPlanning(policy, queue, ingressAuthorization, readAuthorization,
                        lifecycle, reader, planningInbox);
                reconcile = event -> planning.handles(event) ? planning.complete(event) : reconciler.complete(event);
            }
            if (!settings.reviewEvidenceEnabled()) return new DurableDispatcher(queue, ingressAuthorization, reconcile);

            var reviewReader = new GitHubSandboxReviewReader(readAuthorization, jwt, clock, normalizer);
            var reviewInbox = new JdbcSandboxReviewInbox(jdbc, queue, states, evidenceStore);
            var reviews = new SandboxReviewReconciler(policy, queue, ingressAuthorization, reviewReader,
                    (event, snapshot, authorize) -> reviewInbox.reconcile(event, snapshot, () -> {
                        authorize.run();
                        readAuthorization.requireAuthorized(policy.readScope());
                    }));
            var reconcileRemaining = reconcile;
            return new DurableDispatcher(queue, ingressAuthorization,
                    event -> reviews.handles(event) ? reviews.complete(event) : reconcileRemaining.test(event));
        }
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
