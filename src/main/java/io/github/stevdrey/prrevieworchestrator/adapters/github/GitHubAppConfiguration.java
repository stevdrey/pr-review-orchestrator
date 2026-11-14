package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.ports.InstallationAuthorization;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubAppConfiguration.Settings.class)
public class GitHubAppConfiguration {
    @ConfigurationProperties("orchestrator.github")
    public record Settings(boolean enabled, String clientId, Path privateKeyFile, Map<Long, Set<Long>> repositories) {
        public Settings {
            var copy = new java.util.HashMap<Long, Set<Long>>();
            if (repositories != null) {
                repositories.forEach((id, repos) -> copy.put(id, Set.copyOf(repos)));
            }
            repositories = Map.copyOf(copy);
        }
    }

    @Bean
    RepositoryAuthorization repositoryAuthorization(Settings settings, InstallationAuthorization installations) {
        var allowed = new HashSet<RepositoryScope>();
        settings.repositories().forEach((installation, repositories) -> repositories.forEach(
                repository -> allowed.add(new RepositoryScope(installation, repository))));
        return new RepositoryAuthorization(allowed, installations);
    }

    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.github", name = "enabled", havingValue = "true")
    GitHubInstallationTokens gitHubInstallationTokens(Settings settings, RepositoryAuthorization authorization) {
        if (settings.privateKeyFile() == null) {
            throw new IllegalArgumentException("A mounted GitHub App private key file is required");
        }
        var clock = Clock.systemUTC();
        var jwt = new GitHubAppJwt(settings.clientId(), settings.privateKeyFile(), clock);
        // Fail closed at startup when explicitly enabled with an unreadable or invalid key.
        jwt.issue();
        return new GitHubInstallationTokens(authorization, jwt, clock);
    }
}
