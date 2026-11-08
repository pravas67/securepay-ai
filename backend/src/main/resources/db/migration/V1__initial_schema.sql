-- Users of the application. Passwords are BCrypt hashes.
CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Payments. Every row belongs to exactly one user; all queries filter by user_id.
CREATE TABLE payment (
    id          BIGSERIAL PRIMARY KEY,
    reference   VARCHAR(32)    NOT NULL UNIQUE,
    user_id     BIGINT         NOT NULL REFERENCES app_user (id),
    amount      NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency    VARCHAR(3)     NOT NULL,
    recipient   VARCHAR(128)   NOT NULL,
    description VARCHAR(255),
    status      VARCHAR(32)    NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_user_created ON payment (user_id, created_at DESC);

-- Simulated wallet: one per user. No real funds.
CREATE TABLE wallet (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT         NOT NULL UNIQUE REFERENCES app_user (id),
    balance    NUMERIC(19, 2) NOT NULL CHECK (balance >= 0),
    currency   VARCHAR(3)     NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Append-only audit trail. Never store secrets here.
CREATE TABLE audit_log (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       REFERENCES app_user (id),
    action     VARCHAR(64)  NOT NULL,
    details    TEXT,
    outcome    VARCHAR(16)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_user_created ON audit_log (user_id, created_at DESC);

-- Enforce append-only at the database level, so even buggy application code
-- cannot rewrite history.
CREATE FUNCTION audit_log_block_changes() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_block_changes();
