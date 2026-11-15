package io.github.stevdrey.prrevieworchestrator.adapters.github.webhook;

import io.github.stevdrey.prrevieworchestrator.application.AcceptDelivery;
import io.github.stevdrey.prrevieworchestrator.domain.AuthorizationUnavailable;
import io.github.stevdrey.prrevieworchestrator.ports.DeliveryInbox;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
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
    private static final Pattern DELIVERY_ID = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern EVENT_TYPE = Pattern.compile("[a-z_]{1,64}");

    private final GitHubWebhookSignature signature;
    private final GitHubWebhookConfiguration.Settings settings;
    private final AcceptDelivery acceptance;
    private final Clock clock;

    public GitHubWebhookController(GitHubWebhookSignature signature, GitHubWebhookConfiguration.Settings settings,
            AcceptDelivery acceptance, ObjectProvider<Clock> clock) {
        this.signature = signature;
        this.settings = settings;
        this.acceptance = acceptance;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
        // Fail startup, not every delivery, when the inbox store lacks required capabilities.
        acceptance.requireCapabilities();
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<?> receive(HttpServletRequest request) {
        try {
            String contentType = single(request, "Content-Type");
            if (!supportedJson(contentType)
                    || (request.getHeader("Content-Encoding") != null
                        && !"identity".equalsIgnoreCase(single(request, "Content-Encoding")))) {
                return error(415, "UNSUPPORTED_MEDIA_TYPE");
            }
            String delivery = single(request, "X-GitHub-Delivery");
            String type = single(request, "X-GitHub-Event");
            if (delivery == null || !DELIVERY_ID.matcher(delivery).matches()
                    || type == null || !EVENT_TYPE.matcher(type).matches()) {
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
            var event = GitHubWebhookPayload.route(type, UUID.fromString(delivery).toString(), root, body, clock);
            var receipt = acceptance.accept(event);
            return ResponseEntity.status(receipt.duplicate() ? 200 : 202)
                    .header("X-Correlation-ID", receipt.correlationId().toString())
                    .body(Map.of("status", receipt.duplicate() ? "DUPLICATE" : "ACCEPTED"));
        } catch (GitHubWebhookPayload.UnsupportedEvent ignored) {
            // Authenticated but unrouted: acknowledge without work so GitHub does not log a failed delivery.
            return ResponseEntity.noContent().build();
        } catch (DeliveryInbox.Conflict ignored) {
            return error(409, "DELIVERY_CONFLICT");
        } catch (AuthorizationUnavailable ignored) {
            // A membership-authority outage is transient, not a denial; keep it redeliverable.
            return error(503, "INGRESS_UNAVAILABLE");
        } catch (SecurityException ignored) {
            return error(403, "REPOSITORY_DENIED");
        } catch (IllegalArgumentException | IOException ignored) {
            return error(400, "MALFORMED_REQUEST");
        } catch (DataAccessException | TransactionException | WebhookUnavailableException ignored) {
            return error(503, "INGRESS_UNAVAILABLE");
        }
    }

    private static boolean supportedJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType media = MediaType.parseMediaType(contentType);
            return "application".equalsIgnoreCase(media.getType()) && "json".equalsIgnoreCase(media.getSubtype())
                    && (media.getCharset() == null || StandardCharsets.UTF_8.equals(media.getCharset()));
        } catch (IllegalArgumentException unparseable) {
            // Invalid media types and unknown charsets are unsupported, not malformed deliveries.
            return false;
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
