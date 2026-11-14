package io.github.stevdrey.prrevieworchestrator.adapters.github.webhook;

import java.nio.file.Path;
import io.github.stevdrey.prrevieworchestrator.application.AcceptDelivery;
import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.ports.DeliveryInbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubWebhookConfiguration.Settings.class)
public class GitHubWebhookConfiguration {
    @ConfigurationProperties("orchestrator.github.webhook")
    public record Settings(Path secretFile, Path previousSecretFile, Integer maxPayloadBytes) {
        public int payloadLimit() {
            return maxPayloadBytes == null ? 1_048_576 : maxPayloadBytes;
        }
    }

    @Bean
    AcceptDelivery acceptDelivery(RepositoryAuthorization authorization, DeliveryInbox inbox) {
        return new AcceptDelivery(authorization, inbox);
    }

    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.github.webhook", name = "enabled", havingValue = "true")
    GitHubWebhookSignature gitHubWebhookSignature(Settings settings) {
        if (settings.payloadLimit() < 1 || settings.payloadLimit() > 25 * 1024 * 1024) {
            throw new IllegalArgumentException("Webhook payload limit must be between 1 and 26214400 bytes");
        }
        return new GitHubWebhookSignature(settings.secretFile(), settings.previousSecretFile());
    }
}
