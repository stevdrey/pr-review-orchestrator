package io.github.stevdrey.prrevieworchestrator.domain;

import java.util.regex.Pattern;

/** The single full lowercase SHA-1/SHA-256 commit ID format accepted everywhere. */
public final class CommitSha {
    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private CommitSha() {}

    public static boolean isValid(String sha) {
        return sha != null && FORMAT.matcher(sha).matches();
    }

    public static void require(String sha) {
        if (!isValid(sha)) throw new IllegalArgumentException("Invalid head SHA");
    }
}
