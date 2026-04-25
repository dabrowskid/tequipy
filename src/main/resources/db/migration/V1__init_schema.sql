CREATE TABLE equipment (
    id              UUID          PRIMARY KEY,
    type            VARCHAR(32)   NOT NULL CHECK (type IN ('MAIN_COMPUTER', 'MONITOR', 'KEYBOARD', 'MOUSE')),
    brand           TEXT          NOT NULL,
    model           TEXT          NOT NULL,
    status          VARCHAR(32)   NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'RESERVED', 'ASSIGNED', 'RETIRED')),
    condition_score NUMERIC(3, 2) NOT NULL CHECK (condition_score >= 0.0 AND condition_score <= 1.0),
    purchase_date   DATE          NOT NULL,
    retired_reason  TEXT,
    retired_at      TIMESTAMPTZ,
    version         INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_equipment_status_type ON equipment (status, type);

CREATE TABLE allocation_request (
    id             UUID        PRIMARY KEY,
    employee_id    UUID        NOT NULL,
    policy         JSONB       NOT NULL,
    state          VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'RESERVED', 'CONFIRMED', 'CANCELLED', 'FAILED')),
    failure_reason TEXT,
    version        INT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE allocation_equipment (
    allocation_id UUID NOT NULL REFERENCES allocation_request (id),
    equipment_id  UUID NOT NULL REFERENCES equipment (id),
    slot_index    INT  NOT NULL,
    PRIMARY KEY (allocation_id, equipment_id)
);

CREATE UNIQUE INDEX idx_allocation_equipment_active ON allocation_equipment (equipment_id);

CREATE TABLE outbox (
    id             UUID        PRIMARY KEY,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
