-- Change events waiting to be published. A row is written in the same transaction as
-- the employee change it describes, so the two can never disagree; a relay publishes
-- rows in id order and stamps published_at once the broker has acknowledged them.
CREATE TABLE outbox (
    id              BIGSERIAL   PRIMARY KEY,
    event_id        UUID        NOT NULL UNIQUE,
    tenant_id       TEXT        NOT NULL,
    aggregate_id    TEXT        NOT NULL,
    event_type      TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT         NOT NULL DEFAULT 0,
    last_error_code TEXT
);

-- The relay only ever reads unpublished rows; keep that index small.
CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;
