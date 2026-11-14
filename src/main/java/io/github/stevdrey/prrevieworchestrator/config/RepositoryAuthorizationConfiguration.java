package io.github.stevdrey.prrevieworchestrator.config;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.ports.InstallationAuthorization;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provider-independent authorization gate, available whether or not any credential adapter is enabled. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RepositoryAuthorizationConfiguration.Allowlist.class)
public class RepositoryAuthorizationConfiguration {
    /** Deployment allowlist of stable installation ID to repository IDs; empty denies all. */
    @ConfigurationProperties("orchestrator.github")
    public record Allowlist(Map<Long, Set<Long>> repositories) {
        public Allowlist {
            if (repositories == null) {
                repositories = Map.of();
            }
            repositories.entrySet().forEach(Allowlist::requireRepositories);
            repositories = repositories.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        }

        private static void requireRepositories(Map.Entry<Long, Set<Long>> entry) {
            Set<Long> repositories = entry.getValue();
            if (entry.getKey() == null || repositories == null || repositories.isEmpty()
                    || repositories.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("GitHub repository allowlist entry for installation "
                        + entry.getKey() + " must list repository IDs");
            }
        }
    }

    @Bean
    RepositoryAuthorization repositoryAuthorization(Allowlist allowlist, InstallationAuthorization installations) {
        Set<RepositoryScope> allowed = allowlist.repositories().entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(repository -> new RepositoryScope(entry.getKey(), repository)))
                .collect(Collectors.toUnmodifiableSet());
        return new RepositoryAuthorization(allowed, installations);
    }
}
