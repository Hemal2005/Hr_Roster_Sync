-- Core roster tables. Every table carries tenant_id and every key starts with it, so a
-- row can only ever be addressed together with its tenant.

CREATE TABLE tenant (
    id   TEXT PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE office (
    tenant_id TEXT    NOT NULL REFERENCES tenant (id),
    code      TEXT    NOT NULL,
    active    BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tenant_id, code)
);

CREATE TABLE employee (
    tenant_id          TEXT        NOT NULL REFERENCES tenant (id),
    employee_id        TEXT        NOT NULL,

    -- HR-owned: written only by the HR apply path
    first_name         TEXT        NOT NULL,
    last_name          TEXT,
    email              TEXT        NOT NULL,
    phone              TEXT        NOT NULL,
    manager_id         TEXT,
    office_code        TEXT        NOT NULL,
    shift_code         TEXT        NOT NULL,
    status             TEXT        NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    hr_version         BIGINT      NOT NULL CHECK (hr_version > 0),
    hr_payload_hash    BYTEA       NOT NULL,
    last_hr_event_at   TIMESTAMPTZ NOT NULL,

    -- Transport-owned: written only by the transport admin path, with its own version
    pickup_point       TEXT,
    special_assistance TEXT,
    transport_version  BIGINT      NOT NULL DEFAULT 0 CHECK (transport_version >= 0),

    -- floorMod(murmur3(employee_id), N), computed in Java so file rows and stored rows agree
    bucket             SMALLINT    NOT NULL CHECK (bucket >= 0),

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, employee_id),
    FOREIGN KEY (tenant_id, office_code) REFERENCES office (tenant_id, code)
);

CREATE INDEX employee_tenant_bucket_idx ON employee (tenant_id, bucket);
CREATE INDEX employee_tenant_status_idx ON employee (tenant_id, status);

-- One row per applied HR event. Stale and duplicate arrivals repeat an existing key and
-- are counted in metrics instead, so the unique key is the database-level guarantee
-- that the same change is never applied twice.
CREATE TABLE employee_event_log (
    id            BIGSERIAL   PRIMARY KEY,
    tenant_id     TEXT        NOT NULL,
    source_system TEXT        NOT NULL,
    employee_id   TEXT        NOT NULL,
    version       BIGINT      NOT NULL CHECK (version > 0),
    outcome       TEXT        NOT NULL CHECK (outcome IN ('APPLIED')),
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT employee_event_log_idempotency_key UNIQUE (tenant_id, source_system, employee_id, version),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employee (tenant_id, employee_id)
);
