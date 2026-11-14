package io.github.stevdrey.prrevieworchestrator.adapters.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.stevdrey.prrevieworchestrator.application.RepositoryAuthorization;
import io.github.stevdrey.prrevieworchestrator.ports.DeliveryInbox;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GitHubWebhookSecurityTest {
    private static final String CURRENT = "fixture-current-secret-0123456789012345";
    private static final String PREVIOUS = "fixture-previous-secret-012345678901234";

    @TempDir
    Path directory;

    @Test
    void signatureCoversExactUtf8BytesAndRejectsMalformedOrTamperedBodies() throws Exception {
        Path file = Files.writeString(directory.resolve("secret"), CURRENT);
        var verifier = new GitHubWebhookSignature(file, null);
        byte[] body = "{\"title\":\"café 🌎\"}\n".getBytes(StandardCharsets.UTF_8);
        String signature = sign(CURRENT, body);
        assertThat(verifier.verify(signature, body)).isTrue();
        assertThat(verifier.verify(signature, "{\"title\":\"café 🌎\"}".getBytes(StandardCharsets.UTF_8))).isFalse();
        for (String invalid : new String[] {"", "sha1=" + "a".repeat(40), "sha256=xyz", "sha256=" + "0".repeat(64)}) {
            assertThat(verifier.verify(invalid, body)).isFalse();
        }
        assertThat(verifier.verify(null, body)).isFalse();
    }

    @Test
    void rotationReloadsFilesAndRetiredSecretsStopWorking() throws Exception {
        Path current = Files.writeString(directory.resolve("current"), CURRENT);
        Path previous = Files.writeString(directory.resolve("previous"), PREVIOUS);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        var verifier = new GitHubWebhookSignature(current, previous);
        assertThat(verifier.verify(sign(CURRENT, body), body)).isTrue();
        assertThat(verifier.verify(sign(PREVIOUS, body), body)).isTrue();
        Files.writeString(previous, "another-retired-secret-0123456789012345");
        assertThat(verifier.verify(sign(PREVIOUS, body), body)).isFalse();
        Files.writeString(current, PREVIOUS);
        assertThat(verifier.verify(sign(PREVIOUS, body), body)).isTrue();
        assertThat(verifier.verify(sign(CURRENT, body), body)).isFalse();
        Files.delete(current);
        assertThrows(IllegalStateException.class, () -> verifier.verify(sign(PREVIOUS, body), body));
    }

    @Test
    void invalidSecretConfigurationFailsClosedWithoutEchoingContent() throws Exception {
        for (String value : new String[] {"private-fixture-secret", "x".repeat(4097)}) {
            Path file = Files.writeString(directory.resolve("secret"), value);
            var failure = assertThrows(IllegalStateException.class, () -> new GitHubWebhookSignature(file, null));
            assertThat(failure.getMessage()).doesNotContain(value);
        }
        assertThrows(IllegalStateException.class, () -> new GitHubWebhookSignature(null, null));
    }

    @Test
    void endpointIsOptInAndExplicitEnablementRequiresValidSecretAndLimit() throws Exception {
        var runner = new ApplicationContextRunner().withUserConfiguration(GitHubWebhookConfiguration.class,
                        GitHubWebhookController.class)
                .withBean(RepositoryAuthorization.class, () -> new RepositoryAuthorization(Set.of(), scope -> false))
                .withBean(DeliveryInbox.class, () -> delivery -> new DeliveryInbox.Receipt(false, delivery.correlationId()));
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(GitHubWebhookController.class).doesNotHaveBean(GitHubWebhookSignature.class));
        runner.withPropertyValues("orchestrator.github.webhook.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        Path file = Files.writeString(directory.resolve("secret"), CURRENT);
        var enabled = runner.withPropertyValues("orchestrator.github.webhook.enabled=true",
                "orchestrator.github.webhook.secret-file=" + file);
        enabled.run(context -> assertThat(context).hasNotFailed().hasSingleBean(GitHubWebhookController.class));
        for (String limit : new String[] {"0", "26214401"}) {
            enabled.withPropertyValues("orchestrator.github.webhook.max-payload-bytes=" + limit)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
