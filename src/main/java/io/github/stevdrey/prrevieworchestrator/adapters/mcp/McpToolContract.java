package io.github.stevdrey.prrevieworchestrator.adapters.mcp;

import io.github.stevdrey.prrevieworchestrator.application.McpTools.Call;
import io.github.stevdrey.prrevieworchestrator.application.McpTools.Tool;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

final class McpToolContract {
    private static final Pattern UUID_FORMAT = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Map<String, ?> UUID_SCHEMA = Map.of("type", "string", "pattern", "^" + UUID_FORMAT.pattern() + "$");
    private static final List<String> TARGET = List.of("installationId", "repositoryId", "prNumber", "headSha");
    static final List<Map<String, Object>> TOOLS = Arrays.stream(Tool.values()).map(McpToolContract::describe).toList();

    private McpToolContract() {}

    private static Map<String, Object> describe(Tool tool) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("installationId", Map.of("type", "integer", "minimum", 1, "maximum", Long.MAX_VALUE));
        properties.put("repositoryId", Map.of("type", "integer", "minimum", 1, "maximum", Long.MAX_VALUE));
        properties.put("prNumber", Map.of("type", "integer", "minimum", 1, "maximum", Integer.MAX_VALUE));
        properties.put("headSha", Map.of("type", "string", "pattern", "^([0-9a-f]{40}|[0-9a-f]{64})$"));
        var required = new ArrayList<>(TARGET);
        if (tool == Tool.list_findings) properties.put("after", UUID_SCHEMA);
        if (tool.command()) {
            properties.put("commandId", UUID_SCHEMA);
            properties.put("expectedVersion", Map.of("type", "integer", "minimum", 0, "maximum", Long.MAX_VALUE - 1));
            required.addAll(List.of("commandId", "expectedVersion"));
        }
        String description = switch (tool) {
            case get_pr_status -> "Read the locally tracked PR state for the specified full head SHA.";
            case list_findings -> "Read redacted findings for the tracked head. Pass nextCursor as after to continue.";
            case pause_cycle -> "Pause the local review cycle using an operator-approved command ID and state version.";
            case request_review -> "Request a review using an operator-approved command ID and state version. "
                    + "Native provider dispatch from MCP is currently unavailable.";
        };
        return Map.of("name", tool.name(), "description", description,
                "inputSchema", Map.of("type", "object", "properties", properties, "required", required,
                        "additionalProperties", false),
                "annotations", Map.of("readOnlyHint", !tool.command(), "destructiveHint", tool.command(),
                        "idempotentHint", true, "openWorldHint", false));
    }

    static Call parse(JsonNode params) {
        if (!params.isObject() || !params.path("name").isString() || !params.path("arguments").isObject()) {
            throw new IllegalArgumentException("Invalid tool call");
        }
        Tool tool = Tool.valueOf(params.path("name").asText());
        JsonNode args = params.path("arguments");
        for (String key : args.propertyNames()) {
            if (!TARGET.contains(key) && !(tool == Tool.list_findings && key.equals("after"))
                    && !(tool.command() && (key.equals("commandId") || key.equals("expectedVersion")))) {
                throw new IllegalArgumentException("Unexpected argument");
            }
        }
        if (!args.path("headSha").isString()) throw new IllegalArgumentException("Invalid head SHA");
        var scope = new RepositoryScope(integer(args.path("installationId")), integer(args.path("repositoryId")));
        var pr = new PullRequestId(scope, Math.toIntExact(integer(args.path("prNumber"))));
        return new Call(tool, pr, args.path("headSha").asText(null),
                args.has("after") ? uuid(args.path("after")) : null,
                tool.command() ? uuid(args.path("commandId")) : null,
                tool.command() ? integer(args.path("expectedVersion")) : null);
    }

    private static long integer(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("Invalid number");
        return value.longValue();
    }

    private static UUID uuid(JsonNode value) {
        if (!value.isString() || !UUID_FORMAT.matcher(value.asText()).matches()) {
            throw new IllegalArgumentException("Invalid UUID");
        }
        return UUID.fromString(value.asText());
    }
}
