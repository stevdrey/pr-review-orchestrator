package io.github.stevdrey.prrevieworchestrator.adapters.github;

import java.util.Map;

/** No arbitrary permission map or write permission can be supplied by a caller. */
public enum GitHubReadOperation {
    PULL_REQUESTS("pull_requests"),
    COMMENTS("issues"),
    CHECKS("checks"),
    WORKFLOWS("actions");

    private final String permission;

    GitHubReadOperation(String permission) {
        this.permission = permission;
    }

    public Map<String, String> permissions() {
        return Map.of("metadata", "read", permission, "read");
    }
}
