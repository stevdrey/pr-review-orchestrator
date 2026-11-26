package io.github.stevdrey.prrevieworchestrator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import io.github.stevdrey.prrevieworchestrator.config.CodexReviewConfiguration;
import io.github.stevdrey.prrevieworchestrator.config.RepositoryAuthorizationConfiguration.Allowlist;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcReviewStateStore;
import io.github.stevdrey.prrevieworchestrator.adapters.persistence.JdbcSandboxCodexBudget.Terms;
import io.github.stevdrey.prrevieworchestrator.adapters.github.GitHubSandboxUserCredentials;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequest;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewRequestFailure;
import io.github.stevdrey.prrevieworchestrator.ports.CodexApproval;
import io.github.stevdrey.prrevieworchestrator.ports.CodexUserCredentials;
import io.github.stevdrey.prrevieworchestrator.ports.ReviewProvider;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CodexReviewConfigurationTest {
    private static final String HEAD = "a".repeat(40);
    private static final ReviewRequest.Trigger TRIGGER = ReviewRequest.Trigger.PR_SYNCHRONIZE;
    private static final PullRequestId PR = new PullRequestId(new RepositoryScope(101, 1001), 35);
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
                .withBean(JdbcReviewStateStore.class, () -> mock(JdbcReviewStateStore.class))
                .withBean(RepositoryAuthorization.class, () -> mock(RepositoryAuthorization.class))
                .run(app -> {
                    assertThat(app).hasFailed();
                    // Must fail because approved ports are missing, not for an incidental reason.
                    assertThat(app.getStartupFailure()).rootCause().isInstanceOfSatisfying(NoSuchBeanDefinitionException.class,
                            missing -> assertThat(missing.getBeanType()).isIn(CodexApproval.class, CodexUserCredentials.class));
                });
    }

    @Test void pinnedSandboxStillRequiresApprovedPolicy() {
        oneShotWithBudget().run(app -> {
            assertThat(app).hasFailed();
            assertThat(app.getStartupFailure()).rootCause().isInstanceOfSatisfying(NoSuchBeanDefinitionException.class,
                    missing -> assertEquals(CodexApproval.class, missing.getBeanType()));
        });
    }

    @Test void oneShotWithoutTrustedBudgetTermsCannotRegisterTheRunner() {
        oneShot().withBean(CodexApproval.class, () -> mock(CodexApproval.class)).run(app -> {
            assertThat(app).hasFailed();
            assertThat(app.getStartupFailure()).rootCause().isInstanceOfSatisfying(NoSuchBeanDefinitionException.class,
                    missing -> assertEquals(Terms.class, missing.getBeanType()));
        });
    }

    @Test void oneShotRegistersOnlyTheOperatorRunnerWithAnApprovedPolicy() {
        var approval = mock(CodexApproval.class);
        oneShotWithBudget().withBean(CodexApproval.class, () -> approval).run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(ApplicationRunner.class);
            verifyNoInteractions(approval);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.main.web-application-type=servlet", "orchestrator.dispatch.enabled=true",
            "orchestrator.github.webhook.enabled=true"})
    void oneShotRequiresAnIsolatedTerminalProcess(String conflictingSetting) {
        oneShotWithBudget().withPropertyValues(conflictingSetting)
                .withBean(CodexApproval.class, () -> mock(CodexApproval.class)).run(app -> {
                    assertThat(app).hasFailed();
                    assertThat(app.getStartupFailure()).rootCause().hasMessage(
                            "One-shot sandbox review requires a terminal-only process and one authorized repository");
                });
    }

    @Test void pinnedSandboxWiresOAuthWithoutAnImplicitGrant() {
        sandboxOAuth().withBean(CodexApproval.class, () -> mock(CodexApproval.class)).run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(CodexUserCredentials.class);
            var credentials = app.getBean(CodexUserCredentials.class);
            assertInstanceOf(GitHubSandboxUserCredentials.class, credentials);
            var denied = assertThrows(ReviewRequestFailure.class, () -> credentials.current(PR.scope()));
            assertEquals(ReviewRequestFailure.Code.ACTOR_DENIED, denied.code());
        });
    }

    @Test void pinnedSandboxRejectsOtherRequestsBeforeCheckingCredentialsOrBudget() {
        var credentials = mock(CodexUserCredentials.class);
        var approval = mock(CodexApproval.class);
        sandbox().withBean(CodexUserCredentials.class, () -> credentials)
                .withBean(CodexApproval.class, () -> approval).run(app -> {
                    assertThat(app).hasNotFailed().hasSingleBean(ReviewProvider.class);
                    var provider = app.getBean(ReviewProvider.class);
                    var wrongPr = new PullRequestId(PR.scope(), 36);
                    for (var invalid : List.of(
                            new ReviewRequest(wrongPr, HEAD, "BASELINE", "codex", TRIGGER, 42, false, 1),
                            new ReviewRequest(PR, "b".repeat(40), "BASELINE", "codex", TRIGGER, 42, false, 1),
                            new ReviewRequest(PR, HEAD, "BASELINE", "codex", TRIGGER, 42, false, 0),
                            new ReviewRequest(PR, HEAD, "BASELINE", "codex", TRIGGER, 43, false, 1),
                            new ReviewRequest(PR, HEAD, "BASELINE", "codex", TRIGGER, 42, true, 1),
                            new ReviewRequest(PR, HEAD, "BASELINE", "codex", ReviewRequest.Trigger.COMMENT, 42, false, 1))) {
                        var failure = assertThrows(ReviewRequestFailure.class, () -> provider.request(invalid));
                        assertEquals(ReviewRequestFailure.Code.POLICY_DENIED, failure.code());
                    }
                    verifyNoInteractions(approval, credentials, app.getBean(PlatformTransactionManager.class));
                });
    }

    private ApplicationContextRunner sandbox() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT current_setting('transaction_timeout', true)", String.class)).thenReturn("0");
        return context.withPropertyValues("orchestrator.codex.enabled=true", "orchestrator.codex.actor-id=42",
                        "orchestrator.codex.sandbox.enabled=true", "orchestrator.codex.sandbox.installation-id=101",
                        "orchestrator.codex.sandbox.repository-id=1001", "orchestrator.codex.sandbox.pr-number=35",
                        "orchestrator.codex.sandbox.head=" + HEAD, "orchestrator.codex.sandbox.trigger=PR_SYNCHRONIZE",
                        "orchestrator.codex.sandbox.head-epoch=1")
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(JdbcReviewStateStore.class, () -> mock(JdbcReviewStateStore.class))
                .withBean(RepositoryAuthorization.class, () -> mock(RepositoryAuthorization.class));
    }

    private ApplicationContextRunner sandboxOAuth() {
        return sandbox().withPropertyValues("orchestrator.codex.sandbox.oauth.enabled=true",
                "orchestrator.codex.sandbox.oauth.client-id=Iv1.fixture",
                "orchestrator.codex.sandbox.oauth.repository=sandbox/review-fixture");
    }

    private ApplicationContextRunner oneShot() {
        return sandboxOAuth().withPropertyValues("orchestrator.codex.sandbox.trigger=SANDBOX_OPERATOR",
                "orchestrator.codex.sandbox.one-shot-enabled=true", "spring.main.web-application-type=none")
                .withBean(Allowlist.class, () -> new Allowlist(Map.of(101L, Set.of(1001L))));
    }

    private ApplicationContextRunner oneShotWithBudget() {
        return oneShot().withBean(Terms.class, () -> new Terms("fixture_units", 4, 5, 10, 12));
    }
}
