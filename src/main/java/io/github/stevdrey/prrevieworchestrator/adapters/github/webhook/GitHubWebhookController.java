package io.github.stevdrey.prrevieworchestrator.adapters.github.webhook;

import io.github.stevdrey.prrevieworchestrator.application.AcceptDelivery;
import io.github.stevdrey.prrevieworchestrator.ports.DeliveryInbox;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "orchestrator.github.webhook", name = "enabled", havingValue = "true")
public class GitHubWebhookController {
    private final GitHubWebhookSignature signature;
    private final GitHubWebhookConfiguration.Settings settings;
    private final AcceptDelivery acceptance;

    public GitHubWebhookController(GitHubWebhookSignature signature, GitHubWebhookConfiguration.Settings settings,
            AcceptDelivery acceptance) {
        this.signature = signature;
        this.settings = settings;
        this.acceptance = acceptance;
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<?> receive(HttpServletRequest request) {
        try {
            String contentType = single(request, "Content-Type");
            MediaType media = contentType == null ? null : MediaType.parseMediaType(contentType);
            if (media == null || !"application".equalsIgnoreCase(media.getType()) || !"json".equalsIgnoreCase(media.getSubtype())
                    || (media.getCharset() != null && !StandardCharsets.UTF_8.equals(media.getCharset()))
                    || (request.getHeader("Content-Encoding") != null
                        && !"identity".equalsIgnoreCase(single(request, "Content-Encoding")))) {
                return error(415, "UNSUPPORTED_MEDIA_TYPE");
            }
            String delivery = single(request, "X-GitHub-Delivery");
            String type = single(request, "X-GitHub-Event");
            if (delivery == null || !delivery.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
                    || type == null || !type.matches("[a-z_]{1,64}")) {
                return error(400, "INVALID_DELIVERY_HEADERS");
            }
            String signatureHeader = single(request, "X-Hub-Signature-256");
            if (request.getContentLengthLong() > settings.payloadLimit()) {
                return error(413, "PAYLOAD_TOO_LARGE");
            }
            byte[] body = request.getInputStream().readNBytes(settings.payloadLimit() + 1);
            if (body.length > settings.payloadLimit()) {
                return error(413, "PAYLOAD_TOO_LARGE");
            }
            if (!signature.verify(signatureHeader, body)) {
                return error(401, "INVALID_SIGNATURE");
            }
            // Never bind or parse the body until its exact raw bytes have been authenticated.
            var root = GitHubWebhookPayload.parse(body);
            if (type.equals("ping")) {
                GitHubWebhookPayload.require(root.path("hook_id").isIntegralNumber()
                        && root.path("hook_id").canConvertToLong() && root.path("hook_id").longValue() > 0);
                return ResponseEntity.noContent().build();
            }
            var event = GitHubWebhookPayload.route(type, UUID.fromString(delivery).toString(), root, body);
            var receipt = acceptance.accept(event);
            return ResponseEntity.status(receipt.duplicate() ? 200 : 202)
                    .header("X-Correlation-ID", receipt.correlationId().toString())
                    .body(Map.of("status", receipt.duplicate() ? "DUPLICATE" : "ACCEPTED"));
        } catch (GitHubWebhookPayload.UnsupportedEvent ignored) {
            return error(422, "UNSUPPORTED_EVENT_OR_ACTION");
        } catch (DeliveryInbox.Conflict ignored) {
            return error(409, "DELIVERY_CONFLICT");
        } catch (SecurityException ignored) {
            return error(403, "REPOSITORY_DENIED");
        } catch (IllegalArgumentException | IOException ignored) {
            return error(400, "MALFORMED_REQUEST");
        } catch (DataAccessException | TransactionException | IllegalStateException ignored) {
            return error(503, "INGRESS_UNAVAILABLE");
        }
    }

    private static String single(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() > 1) {
            throw new IllegalArgumentException("Ambiguous webhook header");
        }
        return values.isEmpty() ? null : values.getFirst();
    }

    private static ResponseEntity<?> error(int status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }
}
