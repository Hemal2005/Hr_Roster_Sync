-- Minimal trips and bookings: just enough to show what an employee's exit does downstream.

CREATE TABLE trip (
    tenant_id    TEXT        NOT NULL REFERENCES tenant (id),
    id           TEXT        NOT NULL,
    office_code  TEXT        NOT NULL,
    pickup_time  TIMESTAMPTZ NOT NULL,
    capacity     INT         NOT NULL CHECK (capacity > 0),
    seats_booked INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, office_code) REFERENCES office (tenant_id, code),
    CHECK (seats_booked >= 0 AND seats_booked <= capacity)
);

CREATE TABLE booking (
    tenant_id     TEXT        NOT NULL,
    id            TEXT        NOT NULL,
    trip_id       TEXT        NOT NULL,
    employee_id   TEXT        NOT NULL,
    status        TEXT        NOT NULL CHECK (status IN ('BOOKED', 'CANCELLED')),
    cancel_reason TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, trip_id) REFERENCES trip (tenant_id, id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employee (tenant_id, employee_id)
);

CREATE INDEX booking_active_by_employee_idx ON booking (tenant_id, employee_id) WHERE status = 'BOOKED';

-- One live seat per employee per trip.
CREATE UNIQUE INDEX booking_one_seat_per_trip_idx ON booking (tenant_id, trip_id, employee_id)
    WHERE status = 'BOOKED';

-- Event ids a consumer has already acted on. Delivery is at least once, so a consumer
-- records the id in the same transaction as its side effect and skips ids it has seen.
CREATE TABLE processed_message (
    consumer     TEXT        NOT NULL,
    event_id     UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
