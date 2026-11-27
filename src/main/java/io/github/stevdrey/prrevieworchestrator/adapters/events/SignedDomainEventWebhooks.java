package io.github.stevdrey.prrevieworchestrator.adapters.events;

import io.github.stevdrey.prrevieworchestrator.domain.PrDomainEvent;
import io.github.stevdrey.prrevieworchestrator.ports.DomainEventPublisher;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;

/** Only deployment-configured HTTPS destinations can receive events. */
final class SignedDomainEventWebhooks implements DomainEventPublisher, AutoCloseable {
    private static final Duration DEADLINE = Duration.ofSeconds(3);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Map<UUID, Destination> destinations;
    private final Path signingKeyDirectory;
    private final HttpClient http;
    private final Clock clock;

    SignedDomainEventWebhooks(Map<UUID, URI> callbacks, Path signingKeyDirectory) {
        this(callbacks, signingKeyDirectory, Clock.systemUTC(), HttpClient.newBuilder()
                .connectTimeout(DEADLINE).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null)).build());
    }

    SignedDomainEventWebhooks(Map<UUID, URI> callbacks, Path signingKeyDirectory, Clock clock, HttpClient http) {
        if (!callbacks.isEmpty() && (signingKeyDirectory == null || !signingKeyDirectory.isAbsolute())) {
            throw new IllegalArgumentException("An absolute signing-key directory is required for domain events");
        }
        var approved = new HashMap<UUID, Destination>();
        callbacks.forEach((id, uri) -> approved.put(id, destination(uri)));
        this.destinations = Map.copyOf(approved);
        this.signingKeyDirectory = signingKeyDirectory;
        this.clock = clock;
        this.http = http;
    }

    @Override
    public Outcome publish(UUID subscriptionId, String callbackFingerprint, PrDomainEvent event) {
        long started = System.nanoTime();
        var destination = destinations.get(subscriptionId);
        if (destination == null || !destination.fingerprint().equals(callbackFingerprint)) return Outcome.RETRY;
        Path keyFile = signingKeyDirectory.resolve(subscriptionId.toString());
        if (!Files.isRegularFile(keyFile)) return Outcome.RETRY;
        try (var source = Files.newInputStream(keyFile)) {
            byte[] key = source.readNBytes(65);
            try {
                if (key.length < 32 || key.length > 64) return Outcome.RETRY;
                return post(destination.uri(), subscriptionId, event, key, started);
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } catch (IOException | GeneralSecurityException ignored) {
            return Outcome.RETRY;
        }
    }

    private Outcome post(URI uri, UUID subscriptionId, PrDomainEvent event, byte[] key, long started)
            throws GeneralSecurityException {
        var pr = event.pr();
        var body = JSON.writeValueAsBytes(new Envelope(event.eventId(), event.name(), event.occurredAt().toString(),
                new Data(PrDomainEvent.SCHEMA_VERSION, pr.scope().installationId(), pr.scope().repositoryId(),
                        pr.number(), event.headSha(), event.stateVersion(), event.traceId(), event.action().name(),
                        event.state().name(), event.ci() == null ? null : event.ci().name())));
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update((event.eventId() + "." + timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        String signature = "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
        long remaining = remainingNanos(started);
        if (remaining <= 0) return Outcome.RETRY;
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                .header("Content-Type", "application/json")
                .header("webhook-id", event.eventId().toString())
                .header("webhook-timestamp", timestamp)
                .header("webhook-signature", signature)
                .header("X-MCP-Subscription-Id", subscriptionId.toString())
                .header("Idempotency-Key", subscriptionId + "." + event.eventId())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        try {
            var response = pending.get(remainingNanos(started), TimeUnit.NANOSECONDS);
            // Status alone is the receipt. Receiver-controlled bodies are never read or logged.
            try {
                response.body().close();
            } catch (IOException ignored) {
                // A body cleanup failure does not invalidate the received status.
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) return Outcome.ACKNOWLEDGED;
            if (status == 408 || status == 429 || status >= 500) return Outcome.RETRY;
            return Outcome.REJECTED;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return Outcome.RETRY;
        } catch (ExecutionException | TimeoutException ignored) {
            return Outcome.RETRY;
        } finally {
            pending.cancel(true);
        }
    }

    private long remainingNanos(long started) {
        return DEADLINE.toNanos() - (System.nanoTime() - started);
    }

    private Destination destination(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || uri.getRawQuery() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Domain event callbacks require a fixed HTTPS URL without user info, query or fragment");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(uri.toASCIIString().getBytes(StandardCharsets.US_ASCII));
            return new Destination(uri, HexFormat.of().formatHex(digest));
        } catch (GeneralSecurityException ignored) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    @Override
    public void close() {
        http.shutdownNow();
    }

    private record Destination(URI uri, String fingerprint) {}

    private record Envelope(UUID eventId, String name, String timestamp, Data data) {}

    private record Data(int schemaVersion, long installationId, long repositoryId, int pullRequestNumber,
            String headSha, long stateVersion, UUID traceId, String action, String state, String ciStatus) {}
}
