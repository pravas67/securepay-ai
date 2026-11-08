-- Which provider transaction a payment corresponds to (null for seeded demo payments).
ALTER TABLE payment ADD COLUMN provider_reference VARCHAR(64);

-- A payment the AI (or the user) has proposed but nobody has approved yet.
-- Approving a draft is the only way a payment gets executed.
CREATE TABLE payment_draft (
    id             BIGSERIAL PRIMARY KEY,
    reference      VARCHAR(32)    NOT NULL UNIQUE,
    user_id        BIGINT         NOT NULL REFERENCES app_user (id),
    amount         NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency       VARCHAR(3)     NOT NULL,
    recipient      VARCHAR(128)   NOT NULL,
    description    VARCHAR(255),
    status         VARCHAR(32)    NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ    NOT NULL,
    decided_at     TIMESTAMPTZ,
    payment_id     BIGINT         REFERENCES payment (id),
    failure_reason VARCHAR(255)
);
CREATE INDEX idx_payment_draft_user_status ON payment_draft (user_id, status);

-- The user approves exactly what they were shown. Once a draft exists, its terms can never change,
-- so an approval can't be redirected to a different amount or recipient.
CREATE FUNCTION payment_draft_freeze_terms() RETURNS trigger AS $$
BEGIN
    IF NEW.user_id     IS DISTINCT FROM OLD.user_id
    OR NEW.amount      IS DISTINCT FROM OLD.amount
    OR NEW.currency    IS DISTINCT FROM OLD.currency
    OR NEW.recipient   IS DISTINCT FROM OLD.recipient
    OR NEW.description IS DISTINCT FROM OLD.description THEN
        RAISE EXCEPTION 'payment_draft terms are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER payment_draft_terms_immutable
    BEFORE UPDATE ON payment_draft
    FOR EACH ROW EXECUTE FUNCTION payment_draft_freeze_terms();
