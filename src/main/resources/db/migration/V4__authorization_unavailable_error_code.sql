-- Preserve applied V3 and existing journal rows; distinguish retryable authority outages.
ALTER TABLE queue_transitions DROP CONSTRAINT queue_transitions_error_code_check;
ALTER TABLE queue_transitions ADD CONSTRAINT queue_transitions_error_code_check
    CHECK (error_code IN ('INTERNAL_ERROR', 'AUTHORIZATION_DENIED', 'AUTHORIZATION_UNAVAILABLE',
        'INVALID_EVENT', 'UNSUPPORTED_COMMAND', 'REMOTE_UNAVAILABLE', 'REMOTE_UNCERTAIN', 'LEASE_EXHAUSTED'));
