-- Target table: the CHECK constraint turns a negative value into SQLState 23514 at write time.
CREATE TABLE spike_fact (
    id         INT PRIMARY KEY,
    value      INT NOT NULL CHECK (value >= 0),
    written_by TEXT NOT NULL
);

-- Dead letters written by the skip listener; tx_active records whether the listener ran inside a transaction.
CREATE TABLE spike_dlq (
    id         BIGSERIAL PRIMARY KEY,
    item_id    INT NOT NULL,
    stage      TEXT NOT NULL,
    tx_active  BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE shedlock (
    name       VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at  TIMESTAMP NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
