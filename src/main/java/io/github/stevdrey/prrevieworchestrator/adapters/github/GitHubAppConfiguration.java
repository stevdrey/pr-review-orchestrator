package io.github.stevdrey.prrevieworchestrator.adapters.github;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in token adapter; `orchestrator.github.enabled` (via the condition below) is the only enablement switch. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubAppConfiguration.Settings.class)
public class GitHubAppConfiguration {
    @ConfigurationProperties("orchestrator.github")
    public record Settings(String clientId, Path privateKeyFile) {}

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
