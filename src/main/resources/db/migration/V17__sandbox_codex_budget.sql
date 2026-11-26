CREATE TABLE sandbox_codex_budget_limits (
    scope_key text PRIMARY KEY,
    usage_unit text NOT NULL CHECK (usage_unit ~ '^[a-z][a-z0-9_-]{0,31}$'),
    capacity bigint NOT NULL CHECK (capacity > 0),
    reserved bigint NOT NULL CHECK (reserved BETWEEN 0 AND capacity)
);

CREATE TABLE sandbox_codex_budget_reservations (
    request_key text PRIMARY KEY REFERENCES review_requests (request_key),
    usage_unit text NOT NULL CHECK (usage_unit ~ '^[a-z][a-z0-9_-]{0,31}$'),
    units bigint NOT NULL CHECK (units > 0),
    reserved_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
