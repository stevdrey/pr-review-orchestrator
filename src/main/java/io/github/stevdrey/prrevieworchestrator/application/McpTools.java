package io.github.stevdrey.prrevieworchestrator.application;

import io.github.stevdrey.prrevieworchestrator.domain.CommitSha;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewConflict;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition;
import io.github.stevdrey.prrevieworchestrator.ports.McpToolStore;
import io.github.stevdrey.prrevieworchestrator.ports.McpToolStore.Access;
import io.github.stevdrey.prrevieworchestrator.ports.McpToolStore.Approval;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** The session and explicit approval come from the service, never from model-provided claims. */
public final class McpTools {
    private static final int FINDINGS_PAGE_SIZE = 50;

    public enum Tool {
        get_pr_status, list_findings, pause_cycle, request_review;

        public boolean command() {
            return this == pause_cycle || this == request_review;
        }
    }

    public record Call(Tool tool, PullRequestId pr, String headSha, UUID after, UUID commandId, Long version) {
        public Call {
            Objects.requireNonNull(tool);
            Objects.requireNonNull(pr);
            CommitSha.require(headSha);
            if (tool.command() ? commandId == null || version == null || version < 0 || version == Long.MAX_VALUE
                    : commandId != null || version != null) {
                throw new IllegalArgumentException("Invalid command fields");
            }
            if (after != null && tool != Tool.list_findings) {
                throw new IllegalArgumentException("Unexpected cursor");
            }
        }
    }

    public record Result(String code, Map<String, ?> data) {
        public boolean error() { return data.isEmpty(); }
    }

    private final McpToolStore store;

    public McpTools(McpToolStore store) {
        this.store = store;
    }

    public Result call(String tokenDigest, Call call) {
        return store.execute(tokenDigest, call.pr(), access -> {
            var result = evaluate(access, call);
            var correlation = call.tool().command() ? call.commandId() : UUID.randomUUID();
            access.audit(correlation, call.tool().name(), call.headSha(), result.code());
            return result;
        });
    }

    private Result evaluate(Access access, Call call) {
        if (call.tool().command()
                && !new Approval(call.tool().name(), call.commandId(), call.headSha(), call.version())
                        .equals(access.session().approval())) {
            return new Result("APPROVAL_REQUIRED", Map.of());
        }
        var current = access.current();
        if (!call.headSha().equals(current.headSha())) return new Result("STALE_HEAD", Map.of());

        if (call.tool().command()) return command(access, call, current.version());

        var data = new LinkedHashMap<String, Object>();
        data.put("headSha", current.headSha());
        data.put("version", current.version());
        if (call.tool() == Tool.get_pr_status) {
            data.put("state", current.state());
            data.put("ci", current.ci());
            data.put("baselineClean", current.baselineCleanSha() != null);
            data.put("deepClean", current.deepCleanSha() != null);
            if (current.intervention() != null) data.put("intervention", current.intervention());
        } else {
            var findings = access.findings(current.headSha(), call.after(), FINDINGS_PAGE_SIZE + 1);
            int count = Math.min(findings.size(), FINDINGS_PAGE_SIZE);
            data.put("findings", findings.subList(0, count));
            if (findings.size() > count) data.put("nextCursor", findings.get(count - 1).id());
        }
        return new Result("OK", data);
    }

    private Result command(Access access, Call call, long currentVersion) {
        if (call.tool() == Tool.request_review) {
            // No approved MCP identity, provider or budget path is configured for native review dispatch.
            return new Result(call.version() == currentVersion ? "PROVIDER_UNAVAILABLE" : "STALE_VERSION", Map.of());
        }
        var transition = new ReviewTransition(call.commandId(), call.version(), call.headSha(),
                ReviewTransition.Action.PAUSE, null, new ReviewTransition.Gates(
                        false, false, false, false, false, ReviewTransition.Ci.UNKNOWN));
        try {
            String code = access.pause(transition) ? "APPLIED" : "ALREADY_APPLIED";
            return new Result(code, Map.of("commandId", call.commandId(), "headSha", call.headSha(),
                    "appliedVersion", call.version() + 1));
        } catch (ReviewConflict conflict) {
            return new Result(conflict.code().name(), Map.of());
        }
    }
}
