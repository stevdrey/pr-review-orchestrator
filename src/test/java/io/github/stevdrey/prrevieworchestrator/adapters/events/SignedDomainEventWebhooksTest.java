package io.github.stevdrey.prrevieworchestrator.adapters.events;

import static io.github.stevdrey.prrevieworchestrator.ports.DomainEventPublisher.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.stevdrey.prrevieworchestrator.domain.PrDomainEvent;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestId;
import io.github.stevdrey.prrevieworchestrator.domain.PullRequestReview.State;
import io.github.stevdrey.prrevieworchestrator.domain.RepositoryScope;
import io.github.stevdrey.prrevieworchestrator.domain.ReviewTransition.Action;
import io.github.stevdrey.prrevieworchestrator.ports.DomainEventPublisher.Outcome;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class SignedDomainEventWebhooksTest {
    private static final URI CALLBACK = URI.create("https://approved.example/events");
    private static final UUID SUBSCRIPTION = UUID.randomUUID();
    private static final String HEAD = "a".repeat(40);
    private static final Instant OCCURRED = Instant.parse("2026-11-27T08:00:00Z");
    private static final Instant FIRST_TRY = OCCURRED.plusSeconds(5);
    private static final PrDomainEvent EVENT = new PrDomainEvent(UUID.randomUUID(),
            new PullRequestId(new RepositoryScope(101, 202), 3), HEAD, 7,
            UUID.randomUUID(), OCCURRED, Action.MARK_READY_FOR_HUMAN, State.READY_FOR_HUMAN, null);

    @TempDir Path keys;

    @Test
    void sendsOnlyTypedMetadataAndSignsTheExactBytesWithAStableIdempotencyKey() throws Exception {
        byte[] firstKey = "a".repeat(32).getBytes(StandardCharsets.US_ASCII);
        Files.write(keys.resolve(SUBSCRIPTION.toString()), firstKey);
        HttpClient http = httpWithStatus(503, 204);
        try (var publisher = publisher(http, FIRST_TRY)) {
            assertEquals(RETRY, publisher.publish(SUBSCRIPTION, fingerprint(), EVENT));
        }

        byte[] secondKey = "b".repeat(32).getBytes(StandardCharsets.US_ASCII);
        Files.write(keys.resolve(SUBSCRIPTION.toString()), secondKey);
        try (var publisher = publisher(http, FIRST_TRY.plusSeconds(5))) {
            assertEquals(ACKNOWLEDGED, publisher.publish(SUBSCRIPTION, fingerprint(), EVENT));
        }
        var captures = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).sendAsync(captures.capture(), any());
        var first = captures.getAllValues().getFirst();
        var retry = captures.getAllValues().getLast();
        var firstBody = body(first);
        var retryBody = body(retry);
        assertArrayEquals(firstBody, retryBody);
        assertEquals(CALLBACK, first.uri());
        assertEquals("POST", first.method());
        assertEquals("application/json", header(first, "Content-Type"));
        assertEquals(EVENT.eventId().toString(), header(first, "webhook-id"));
        assertEquals(SUBSCRIPTION.toString(), header(first, "X-MCP-Subscription-Id"));
        assertEquals(SUBSCRIPTION + "." + EVENT.eventId(), header(first, "Idempotency-Key"));
        assertEquals(header(first, "Idempotency-Key"), header(retry, "Idempotency-Key"));
        assertSignature(first, firstBody, firstKey, FIRST_TRY);
        assertSignature(retry, retryBody, secondKey, FIRST_TRY.plusSeconds(5));

        var json = JsonMapper.builder().build().readTree(firstBody);
        assertEquals(Set.of("eventId", "name", "timestamp", "data"), new HashSet<>(json.propertyNames()));
        assertEquals(EVENT.eventId().toString(), json.get("eventId").asString());
        assertEquals("pr.ready.for.validation", json.get("name").asString());
        assertEquals(OCCURRED.toString(), json.get("timestamp").asString());
        var data = json.get("data");
        assertEquals(Set.of("schemaVersion", "installationId", "repositoryId", "pullRequestNumber", "headSha",
                "stateVersion", "traceId", "action", "state", "ciStatus"), new HashSet<>(data.propertyNames()));
        assertEquals(1, data.get("schemaVersion").asInt());
        assertEquals(101, data.get("installationId").asLong());
        assertEquals(202, data.get("repositoryId").asLong());
        assertEquals(3, data.get("pullRequestNumber").asInt());
        assertEquals(HEAD, data.get("headSha").asString());
        assertEquals(7, data.get("stateVersion").asLong());
        assertEquals(EVENT.traceId().toString(), data.get("traceId").asString());
        assertEquals("MARK_READY_FOR_HUMAN", data.get("action").asString());
        assertEquals("READY_FOR_HUMAN", data.get("state").asString());
    }

    @ParameterizedTest
    @CsvSource({"200,ACKNOWLEDGED", "302,REJECTED", "401,REJECTED", "408,RETRY", "410,REJECTED",
            "413,REJECTED", "429,RETRY", "500,RETRY"})
    void classifiesReceiverStatusWithoutReadingItsBody(int status, Outcome expected) throws Exception {
        Files.write(keys.resolve(SUBSCRIPTION.toString()), new byte[32]);
        try (var publisher = publisher(httpWithStatus(status, status), FIRST_TRY)) {
            assertEquals(expected, publisher.publish(SUBSCRIPTION, fingerprint(), EVENT));
        }
    }

    @Test
    void missingKeyOrUnregisteredDestinationCannotSend() throws Exception {
        var http = mock(HttpClient.class);
        try (var publisher = publisher(http, FIRST_TRY)) {
            assertEquals(RETRY, publisher.publish(SUBSCRIPTION, fingerprint(), EVENT));
            Files.write(keys.resolve(SUBSCRIPTION.toString()), new byte[65]);
            assertEquals(RETRY, publisher.publish(SUBSCRIPTION, fingerprint(), EVENT));
            Files.write(keys.resolve(SUBSCRIPTION.toString()), new byte[32]);
            assertEquals(RETRY, publisher.publish(SUBSCRIPTION, "0".repeat(64), EVENT));
            assertEquals(RETRY, publisher.publish(UUID.randomUUID(), fingerprint(), EVENT));
            verify(http, never()).sendAsync(any(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/events", "https://user@example.com/events",
            "https://example.com/events?credential=value", "https://example.com/#fragment", "/events"})
    void rejectsUnapprovedUriForms(String uri) {
        assertThrows(IllegalArgumentException.class, () -> new SignedDomainEventWebhooks(
                Map.of(SUBSCRIPTION, URI.create(uri)), keys, Clock.systemUTC(), mock(HttpClient.class)));
    }

    private SignedDomainEventWebhooks publisher(HttpClient http, Instant now) {
        return new SignedDomainEventWebhooks(Map.of(SUBSCRIPTION, CALLBACK), keys, Clock.fixed(now, ZoneOffset.UTC), http);
    }

    private String fingerprint() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(CALLBACK.toASCIIString().getBytes(StandardCharsets.US_ASCII)));
    }

    private HttpClient httpWithStatus(int first, int next) {
        HttpClient http = mock(HttpClient.class);
        var firstResponse = CompletableFuture.completedFuture(response(first));
        var nextResponse = CompletableFuture.completedFuture(response(next));
        when(http.<InputStream>sendAsync(any(), any())).thenReturn(firstResponse).thenReturn(nextResponse);
        return http;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> response(int status) {
        var response = (HttpResponse<InputStream>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(new InputStream() {
            @Override
            public int read() {
                return fail("Receiver-controlled body must not be read");
            }
        });
        return response;
    }

    private void assertSignature(HttpRequest request, byte[] body, byte[] key, Instant instant) throws Exception {
        String timestamp = Long.toString(instant.getEpochSecond());
        assertEquals(timestamp, header(request, "webhook-timestamp"));
        Mac verifier = Mac.getInstance("HmacSHA256");
        verifier.init(new SecretKeySpec(key, "HmacSHA256"));
        verifier.update((EVENT.eventId() + "." + timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        assertEquals("v1," + Base64.getEncoder().encodeToString(verifier.doFinal(body)), header(request, "webhook-signature"));
    }

    private String header(HttpRequest request, String name) {
        return request.headers().firstValue(name).orElseThrow();
    }

    private byte[] body(HttpRequest request) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var complete = new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                var chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable failure) { complete.completeExceptionally(failure); }
            @Override public void onComplete() { complete.complete(bytes.toByteArray()); }
        });
        return complete.get(1, TimeUnit.SECONDS);
    }
}
