package io.github.stevdrey.prrevieworchestrator.domain;

import java.time.Instant;
import java.util.UUID;

/** Authenticated transport observation; never authority for a downstream command. No source text. */
public record VerifiedDelivery(UUID id, String deliveryId, String eventType, String action,
        RepositoryScope scope, Integer prNumber, String headSha, String payloadSha256,
        UUID correlationId, Instant verifiedAt) {}
