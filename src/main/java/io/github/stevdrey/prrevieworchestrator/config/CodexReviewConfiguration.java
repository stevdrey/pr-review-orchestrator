package io.github.stevdrey.prrevieworchestrator.config;

import io.github.stevdrey.prrevieworchestrator.adapters.github.*;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcReviewRequestStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcReviewStateStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcSandboxCodexBudget;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.DurableWork;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequest;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure;
import io.github.stevdrey.prrevieworchestrator.ports.*;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CodexReviewConfiguration.SandboxSettings.class)
public class CodexReviewConfiguration {
    @ConfigurationProperties("orchestrator.codex.sandbox")
    record SandboxSettings(boolean enabled, long installationId, long repositoryId, int prNumber, String head,
                           ReviewRequest.Trigger trigger, long headEpoch) {
        ReviewRequest pinnedRequest(long actorId) {
            if (trigger == null || trigger == ReviewRequest.Trigger.COMMENT || trigger == ReviewRequest.Trigger.REVIEW
                    || trigger == ReviewRequest.Trigger.PLANNED_REREVIEW) {
                throw new IllegalArgumentException("Sandbox dispatch requires a supported trigger");
            }
            var pr = new PullRequestId(new RepositoryScope(installationId, repositoryId), prNumber);
            return new ReviewRequest(pr, head, "BASELINE", "codex", trigger, actorId, false, headEpoch);
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.codex", name = "enabled", havingValue = "false", matchIfMissing = true)
    ReviewProvider disabledCodexReviewProvider() {
        return request -> { throw new ReviewRequestFailure(ReviewRequestFailure.Code.DISABLED); };
    }
    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.codex", name = {"enabled", "sandbox.enabled", "sandbox.oauth.enabled"},
            havingValue = "true")
    GitHubSandboxUserCredentials sandboxCodexUserCredentials(RepositoryAuthorization authorization, SandboxSettings sandbox,
            @Value("${orchestrator.codex.actor-id}") long actorId,
            @Value("${orchestrator.codex.sandbox.oauth.client-id}") String clientId,
            @Value("${orchestrator.codex.sandbox.oauth.repository}") String repository) {
        var request = sandbox.pinnedRequest(actorId);
        var scope = new GitHubSandboxDeviceOAuth.Scope(request.pr(), repository, request.headSha(), actorId);
        return new GitHubSandboxUserCredentials(authorization, Clock.systemUTC(), clientId, scope);
    }

    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.codex.sandbox", name = "one-shot-enabled", havingValue = "true")
    ApplicationRunner sandboxCodexOneShot(@Qualifier("sandboxCodexUserCredentials") GitHubSandboxUserCredentials credentials,
            ReviewProvider provider,
            SandboxSettings sandbox, RepositoryAuthorizationConfiguration.Allowlist allowlist, Environment env,
            @Value("${orchestrator.codex.actor-id}") long actorId) {
        var target = sandbox.pinnedRequest(actorId);
        var scope = target.pr().scope();
        if (!"none".equalsIgnoreCase(env.getProperty("spring.main.web-application-type"))
                || env.getProperty("orchestrator.dispatch.enabled", Boolean.class, false)
                || env.getProperty("orchestrator.github.webhook.enabled", Boolean.class, false)
                || !Map.of(scope.installationId(), Set.of(scope.repositoryId())).equals(allowlist.repositories())) {
            throw new IllegalStateException("One-shot sandbox review requires a terminal-only process and one authorized repository");
        }
        var trigger = new SandboxCodexTrigger(target, credentials, provider, Clock.systemUTC());
        return args -> {
            var console = System.console();
            if (console == null || !console.isTerminal()) {
                throw new IllegalStateException("One-shot sandbox review requires an interactive terminal");
            }
            var result = trigger.run(console.writer());
            console.printf("Sandbox request disposition: %s%n", result);
        };
    }

    /** A trusted accounting/budget implementation remains a required dependency. */
    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.codex", name = "enabled", havingValue = "true")
    ReviewProvider codexReviewProvider(JdbcTemplate jdbc, PlatformTransactionManager manager, JdbcReviewStateStore states,
            RepositoryAuthorization authorization, CodexApproval approval, CodexUserCredentials credentials, SandboxSettings sandbox,
            ObjectProvider<JdbcSandboxCodexBudget.Terms> budgetTerms, ObjectProvider<ReviewIntentPolicy> intentPolicy,
            Environment env,
            @Value("${orchestrator.codex.actor-id}") long actorId,
            @Value("${orchestrator.codex.max-requests:6}") int limit,
            @Value("${orchestrator.notifications.enabled:false}") boolean notifications,
            @Value("${orchestrator.codex.sandbox.one-shot-enabled:false}") boolean oneShot) {
        if (sandbox.enabled() && (notifications || limit < 1 || limit > 6)) {
            throw new IllegalArgumentException("Pinned sandbox dispatch requires a valid request limit and disabled notifications");
        }
        if (oneShot && !sandbox.enabled()) throw new IllegalArgumentException("One-shot review requires sandbox mode");
        if (env.getProperty("orchestrator.sandbox-ci.codex-dispatch-enabled", Boolean.class, false)) {
            return plannedProvider(jdbc, manager, states, authorization, approval, credentials, sandbox,
                    intentPolicy.getObject(), budgetTerms.getObject(), env, actorId, limit);
        }
        var budget = oneShot ? new JdbcSandboxCodexBudget(jdbc, budgetTerms.getObject()) : null;
        var adapter = new CodexReviewAdapter(authorization, new JdbcReviewRequestStore(jdbc, manager, states, budget), approval,
                new GitHubCodexComments(credentials, Clock.systemUTC()), actorId, sandbox.enabled() ? 1 : limit,
                oneShot ? CodexReviewAdapter.Mode.SANDBOX_OPERATOR : CodexReviewAdapter.Mode.STANDARD);
        if (!sandbox.enabled()) return adapter;
        var pinned = sandbox.pinnedRequest(actorId);
        return request -> {
            if (!pinned.equals(request)) throw new ReviewRequestFailure(ReviewRequestFailure.Code.POLICY_DENIED);
            return adapter.request(request);
        };
    }
    private ReviewProvider plannedProvider(JdbcTemplate jdbc, PlatformTransactionManager manager,
            JdbcReviewStateStore states, RepositoryAuthorization authorization, CodexApproval approval,
            CodexUserCredentials credentials, SandboxSettings sandbox, ReviewIntentPolicy policy,
            JdbcSandboxCodexBudget.Terms budgetTerms, Environment env, long actorId, int limit) {
        if (sandbox.enabled() || !env.getProperty("orchestrator.dispatch.enabled", Boolean.class, false)
                || !env.getProperty("orchestrator.sandbox-ci.enabled", Boolean.class, false)
                || !env.getProperty("orchestrator.sandbox-ci.synchronize-enabled", Boolean.class, false)) {
            throw new IllegalStateException("Planned Codex dispatch requires the synchronize worker and a dedicated grant");
        }
        var scope = new RepositoryScope(env.getRequiredProperty("orchestrator.sandbox-ci.installation-id", Long.class),
                env.getRequiredProperty("orchestrator.sandbox-ci.repository-id", Long.class));
        var pr = new PullRequestId(scope, env.getRequiredProperty("orchestrator.sandbox-ci.pr-number", Integer.class));
        CodexApproval plannedApproval = request -> {
            var intent = new DurableWork.Intent(pr.number(), request.headSha(), request.stage(),
                    request.provider(), DurableWork.REQUEST_REVIEW);
            if (!policy.permits(pr, intent)) throw new ReviewRequestFailure(ReviewRequestFailure.Code.POLICY_DENIED);
            approval.requirePermitted(request);
        };
        // Local hard budget reservations are held under the PR lock, independent of the injected approval.
        var budget = new JdbcSandboxCodexBudget(jdbc, budgetTerms);
        var adapter = new CodexReviewAdapter(authorization, new JdbcReviewRequestStore(jdbc, manager, states, budget),
                plannedApproval, new GitHubCodexComments(credentials, Clock.systemUTC()), actorId, limit, CodexReviewAdapter.Mode.PLANNED);
        return request -> {
            if (!request.pr().equals(pr) || request.trigger() != ReviewRequest.Trigger.PLANNED_REREVIEW) {
                throw new ReviewRequestFailure(ReviewRequestFailure.Code.POLICY_DENIED);
            }
            return adapter.request(request);
        };
    }
}
