package io.github.stevdrey.prrevieworchestrator.adapters.github;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;

/** Reads the mounted PKCS#8 key on each issuance so atomic key replacement takes effect immediately. */
public final class GitHubAppJwt {
    private final String clientId;
    private final Path privateKeyFile;
    private final Clock clock;

    public GitHubAppJwt(String clientId, Path privateKeyFile, Clock clock) {
        if (clientId == null || !clientId.matches("[A-Za-z0-9_.-]{1,128}")) {
            throw new IllegalArgumentException("A valid GitHub App client ID is required");
        }
        this.clientId = clientId;
        this.privateKeyFile = java.util.Objects.requireNonNull(privateKeyFile);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    String issue() {
        try {
            String pem = Files.readString(privateKeyFile, StandardCharsets.US_ASCII);
            if (!pem.startsWith("-----BEGIN PRIVATE KEY-----")) {
                throw new IllegalArgumentException();
            }
            byte[] der = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").strip());
            var key = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (key.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException();
            }
            long now = clock.instant().getEpochSecond();
            String unsigned = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "."
                    + encode("{\"iat\":" + (now - 60) + ",\"exp\":" + (now + 540) + ",\"iss\":\"" + clientId + "\"}");
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (Exception ignored) {
            // No key bytes, filesystem paths, or library causes in diagnostics.
            throw new IllegalStateException("GitHub App signing unavailable");
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "GitHubAppJwt[REDACTED]";
    }
}
