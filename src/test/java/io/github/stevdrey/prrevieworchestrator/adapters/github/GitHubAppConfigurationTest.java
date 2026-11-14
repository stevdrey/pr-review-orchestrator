package io.github.stevdrey.prrevieworchestrator.adapters.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.config.RepositoryAuthorizationConfiguration;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.ports.InstallationAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GitHubAppConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RepositoryAuthorizationConfiguration.class, GitHubAppConfiguration.class)
            .withBean(InstallationAuthorization.class, () -> scope -> true);

    @Test
    void defaultConfigurationRequiresNoCredentialsAndDeniesEveryRepository() {
        context.run(app -> {
            assertThat(app).hasNotFailed().doesNotHaveBean(GitHubInstallationTokens.class);
            assertThrows(SecurityException.class, () -> app.getBean(RepositoryAuthorization.class)
                    .requireAuthorized(new RepositoryScope(101, 1001)));
        });
    }

    @Test
    void bindsExplicitPairsWithoutAllowingTheirCrossProduct() {
        context.withPropertyValues("orchestrator.github.repositories.101[0]=1001",
                "orchestrator.github.repositories.202[0]=2002").run(app -> {
            assertThat(app).hasNotFailed();
            var authorization = app.getBean(RepositoryAuthorization.class);
            authorization.requireAuthorized(new RepositoryScope(101, 1001));
            authorization.requireAuthorized(new RepositoryScope(202, 2002));
            assertThrows(SecurityException.class, () -> authorization.requireAuthorized(new RepositoryScope(101, 2002)));
        });
    }

    @Test
    void emptyRepositoryListFailsStartupNamingTheInstallation() {
        context.withPropertyValues("orchestrator.github.repositories.101=").run(app -> {
            assertThat(app).hasFailed();
            assertThat(app.getStartupFailure()).rootCause().hasMessageContaining("installation 101");
        });
    }

    @Test
    void explicitEnablementWithoutCredentialsFailsStartup() {
        context.withPropertyValues("orchestrator.github.enabled=true").run(app -> assertThat(app).hasFailed());
    }
}
