CREATE TABLE idempotency_key (
    key           TEXT        NOT NULL,
    operation     VARCHAR(32) NOT NULL,
    allocation_id UUID        NOT NULL REFERENCES allocation_request (id),
    state         VARCHAR(32) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (key, operation)
);

CREATE INDEX idx_idempotency_key_allocation ON idempotency_key (allocation_id);