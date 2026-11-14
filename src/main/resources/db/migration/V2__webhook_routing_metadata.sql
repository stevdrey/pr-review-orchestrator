-- Authenticated, authorized routing metadata only; raw bodies are never persisted.
ALTER TABLE inbox_events
    ADD COLUMN event_action text CHECK (event_action ~ '^[a-z_]{1,64}$'),
    ADD COLUMN pr_number integer CHECK (pr_number > 0);

-- Keep V1/legacy metadata rows unchanged. Protect new ingress against the same signed
-- body being replayed under another (unsigned) delivery/event header.
CREATE UNIQUE INDEX inbox_events_payload_identity_idx
    ON inbox_events (installation_id, repository_id, payload_sha256)
    WHERE event_action IS NOT NULL;
