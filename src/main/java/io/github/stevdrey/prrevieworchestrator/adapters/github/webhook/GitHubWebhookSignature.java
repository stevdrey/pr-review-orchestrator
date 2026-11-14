package io.github.stevdrey.prrevieworchestrator.adapters.github.webhook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Reload mounted secrets per delivery, permitting atomic replacement without keeping old keys cached. */
public final class GitHubWebhookSignature {
    private final Path current;
    private final Path previous;

    public GitHubWebhookSignature(Path current, Path previous) {
        this.current = current;
        this.previous = previous;
        readSecret(current);
        if (previous != null) {
            readSecret(previous);
        }
    }

    public boolean verify(String signature, byte[] body) {
        if (signature == null || !signature.matches("sha256=[0-9a-fA-F]{64}")) {
            return false;
        }
        byte[] supplied = HexFormat.of().parseHex(signature.substring(7));
        boolean valid = MessageDigest.isEqual(supplied, digest(readSecret(current), body));
        if (previous != null) {
            valid |= MessageDigest.isEqual(supplied, digest(readSecret(previous), body));
        }
        return valid;
    }

    private static byte[] readSecret(Path path) {
        if (path == null) {
            throw new IllegalStateException("A readable mounted webhook secret of 32 to 4096 bytes is required");
        }
        try (var input = Files.newInputStream(path)) {
            byte[] secret = input.readNBytes(4097);
            if (secret.length < 32 || secret.length > 4096) {
                throw new IOException();
            }
            return secret;
        } catch (IOException | SecurityException ignored) {
            throw new IllegalStateException("A readable mounted webhook secret of 32 to 4096 bytes is required");
        }
    }

    private static byte[] digest(byte[] secret, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException ignored) {
            throw new IllegalStateException("Webhook signature service unavailable");
        }
    }
}
