-- Nightly reconciliation: a run plans its changes first and applies them only after the
-- safety brake passes or an operator approves, so nothing changes before that decision.

CREATE TABLE reconciliation_run (
    id             UUID        PRIMARY KEY,
    tenant_id      TEXT        NOT NULL REFERENCES tenant (id),
    file_name      TEXT        NOT NULL,
    as_of          TIMESTAMPTZ NOT NULL,
    status         TEXT        NOT NULL CHECK (status IN ('RUNNING', 'PLANNED', 'AWAITING_APPROVAL', 'APPLYING',
                                                          'COMPLETED', 'REJECTED', 'FAILED')),
    submitted_by   TEXT        NOT NULL,
    rows_read      INT         NOT NULL DEFAULT 0,
    rows_invalid   INT         NOT NULL DEFAULT 0,
    to_create      INT         NOT NULL DEFAULT 0,
    to_update      INT         NOT NULL DEFAULT 0,
    to_deactivate  INT         NOT NULL DEFAULT 0,
    conflicts      INT         NOT NULL DEFAULT 0,
    active_before  INT         NOT NULL DEFAULT 0,
    missing_ratio  NUMERIC(7, 6),
    trailer_count  INT,
    trailer_ok     BOOLEAN,
    brake_tripped  BOOLEAN,
    brake_reason   TEXT,
    applied        INT         NOT NULL DEFAULT 0,
    skipped        INT         NOT NULL DEFAULT 0,
    decided_by     TEXT,
    failure_code   TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    planned_at     TIMESTAMPTZ,
    decided_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ
);

CREATE INDEX reconciliation_run_tenant_idx ON reconciliation_run (tenant_id, created_at);

-- Only the changes, never the unchanged rows. payload holds the export row for CREATE and
-- UPDATE; row_version is the export version for those and the stored version for
-- DEACTIVATE, so applying later can tell whether the employee moved on in between.
CREATE TABLE reconciliation_item (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID      NOT NULL REFERENCES reconciliation_run (id) ON DELETE CASCADE,
    employee_id TEXT      NOT NULL,
    action      TEXT      NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'DEACTIVATE')),
    row_version BIGINT    NOT NULL,
    payload     JSONB
);

CREATE INDEX reconciliation_item_run_idx ON reconciliation_item (run_id, id);

-- Line numbers and codes only: never the values of the rejected row.
CREATE TABLE reconciliation_error (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID      NOT NULL REFERENCES reconciliation_run (id) ON DELETE CASCADE,
    line_no     BIGINT    NOT NULL,
    employee_id TEXT,
    error_code  TEXT      NOT NULL
);

CREATE INDEX reconciliation_error_run_idx ON reconciliation_error (run_id, line_no);
