package io.github.stevdrey.prrevieworchestrator.adapters.mcp;

import io.github.stevdrey.prrevieworchestrator.application.McpTools;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationDenied;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationUnavailable;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewStateUnavailable;
import io.github.stevdrey.prrevieworchestrator.domain.Sha256;
import io.github.stevdrey.prrevieworchestrator.ports.McpToolStore;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnProperty(prefix = "orchestrator.mcp", name = "enabled", havingValue = "true")
public class McpController {
    private static final int BODY_LIMIT = 16_384;
    private static final String LATEST = "2025-11-25";
    private static final Set<String> VERSIONS = Set.of(LATEST, "2025-06-18", "2025-03-26");
    private static final Pattern BEARER = Pattern.compile("(?i:Bearer) ([0-9a-f]{64})");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final McpToolStore store;
    private final McpTools tools;

    public McpController(McpToolStore store, McpTools tools) {
        this.store = store;
        this.tools = tools;
    }

    @PostMapping("/mcp")
    public ResponseEntity<?> post(HttpServletRequest request) {
        // This private endpoint accepts native clients only. Refuse browser origins, including "null".
        if (request.getHeader("Origin") != null) return response(403).build();
        try {
            String digest = credentialDigest(request);
            String version = single(request, "MCP-Protocol-Version");
            if (version != null && !VERSIONS.contains(version)) return response(400).build();
            if (!jsonInput(request)) return response(415).build();
            if (request.getContentLengthLong() > BODY_LIMIT) return response(413).build();

            store.authenticate(digest);
            byte[] body = request.getInputStream().readNBytes(BODY_LIMIT + 1);
            if (body.length > BODY_LIMIT) return response(413).build();
            JsonNode message = JSON.readTree(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString());
            return route(digest, message);
        } catch (AuthorizationDenied ignored) {
            return response(401).header("WWW-Authenticate", "Bearer realm=\"pr-review-orchestrator\"").build();
        } catch (AuthorizationUnavailable | ReviewStateUnavailable ignored) {
            return response(503).build();
        } catch (JacksonException | IOException ignored) {
            return rpcError(400, null, -32700, "Parse error");
        } catch (IllegalArgumentException ignored) {
            return response(400).build();
        }
    }

    private ResponseEntity<?> route(String digest, JsonNode message) {
        if (message == null || !message.isObject() || !"2.0".equals(message.path("jsonrpc").stringValue())
                || !message.path("method").isString()) {
            return rpcError(400, null, -32600, "Invalid request");
        }
        String method = message.path("method").asText();
        if (!message.has("id")) {
            // Notifications carry no request ID and must never invoke commands.
            return switch (method) {
                case "notifications/initialized", "notifications/cancelled" -> response(202).build();
                default -> response(400).build();
            };
        }
        JsonNode id = message.path("id");
        if (!id.isString() && !id.isIntegralNumber()) return rpcError(400, null, -32600, "Invalid request");

        JsonNode params = message.path("params");
        try {
            return switch (method) {
                case "initialize" -> result(id, initialize(params));
                case "ping" -> result(id, Map.of());
                case "tools/list" -> {
                    if (params.has("cursor")) throw new IllegalArgumentException("Unexpected tools cursor");
                    yield result(id, Map.of("tools", McpToolContract.TOOLS));
                }
                case "tools/call" -> result(id, toolResult(tools.call(digest, McpToolContract.parse(params))));
                default -> rpcError(200, id, -32601, "Method not found");
            };
        } catch (IllegalArgumentException | ArithmeticException ignored) {
            return rpcError(200, id, -32602, "Invalid params");
        }
    }

    private Map<String, ?> initialize(JsonNode params) {
        JsonNode version = params.path("protocolVersion");
        if (!version.isString() || !params.path("capabilities").isObject() || !params.path("clientInfo").isObject()) {
            throw new IllegalArgumentException("Invalid initialization");
        }
        return Map.of("protocolVersion", VERSIONS.contains(version.asText()) ? version.asText() : LATEST,
                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                "serverInfo", Map.of("name", "pr-review-orchestrator", "version", "1.0.0"));
    }

    private Map<String, ?> toolResult(McpTools.Result result) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("code", result.code());
        payload.putAll(result.data());
        return Map.of("isError", result.error(), "structuredContent", payload,
                "content", List.of(Map.of("type", "text", "text", JSON.writeValueAsString(payload))));
    }

    private String credentialDigest(HttpServletRequest request) {
        String header = single(request, "Authorization");
        if (header == null) throw new AuthorizationDenied();
        var match = BEARER.matcher(header);
        if (!match.matches()) throw new AuthorizationDenied();
        return Sha256.hex(match.group(1).getBytes(StandardCharsets.US_ASCII));
    }

    private boolean jsonInput(HttpServletRequest request) {
        String type = single(request, "Content-Type");
        String encoding = single(request, "Content-Encoding");
        if (type == null || encoding != null && !"identity".equalsIgnoreCase(encoding)) return false;
        var media = MediaType.parseMediaType(type);
        return "application".equals(media.getType()) && "json".equals(media.getSubtype())
                && (media.getCharset() == null || StandardCharsets.UTF_8.equals(media.getCharset()));
    }

    private String single(HttpServletRequest request, String name) {
        var headers = request.getHeaders(name);
        if (!headers.hasMoreElements()) return null;
        String value = headers.nextElement();
        if (headers.hasMoreElements()) throw new IllegalArgumentException("Ambiguous header");
        return value;
    }

    private ResponseEntity<?> result(JsonNode id, Map<String, ?> result) {
        return response(200).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("jsonrpc", "2.0", "id", id, "result", result));
    }

    private ResponseEntity<?> rpcError(int status, JsonNode id, int code, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("error", Map.of("code", code, "message", message));
        return response(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private ResponseEntity.BodyBuilder response(int status) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store");
    }
}
