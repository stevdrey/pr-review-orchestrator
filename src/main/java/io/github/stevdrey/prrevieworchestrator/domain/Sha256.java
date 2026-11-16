package io.github.stevdrey.prrevieworchestrator.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Lowercase hex SHA-256, shared by body hashes and evidence fingerprints. */
public final class Sha256 {
    private Sha256() {}

    public static String hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
