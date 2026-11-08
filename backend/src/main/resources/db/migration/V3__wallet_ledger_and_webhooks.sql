-- Drafts now cover two kinds of money movement: card payments and demo-wallet transfers.
ALTER TABLE payment_draft ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'PAYMENT';
-- Reference of whatever the approval produced (payment or wallet transfer).
ALTER TABLE payment_draft ADD COLUMN result_reference VARCHAR(32);

CREATE OR REPLACE FUNCTION payment_draft_freeze_terms() RETURNS trigger AS $$
BEGIN
    IF NEW.user_id     IS DISTINCT FROM OLD.user_id
    OR NEW.kind        IS DISTINCT FROM OLD.kind
    OR NEW.amount      IS DISTINCT FROM OLD.amount
    OR NEW.currency    IS DISTINCT FROM OLD.currency
    OR NEW.recipient   IS DISTINCT FROM OLD.recipient
    OR NEW.description IS DISTINCT FROM OLD.description THEN
        RAISE EXCEPTION 'payment_draft terms are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Double-entry style ledger for the simulated wallet. Each transfer writes a debit row for the sender
-- and a credit row for the receiver with the same reference. Rows are never changed or removed.
CREATE TABLE wallet_transaction (
    id            BIGSERIAL PRIMARY KEY,
    reference     VARCHAR(32)    NOT NULL,
    wallet_id     BIGINT         NOT NULL REFERENCES wallet (id),
    user_id       BIGINT         NOT NULL REFERENCES app_user (id),
    amount        NUMERIC(19, 2) NOT NULL CHECK (amount <> 0),
    balance_after NUMERIC(19, 2) NOT NULL CHECK (balance_after >= 0),
    counterparty  VARCHAR(64)    NOT NULL,
    description   VARCHAR(255),
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_wallet_tx_user_created ON wallet_transaction (user_id, created_at DESC);

CREATE FUNCTION block_changes() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER wallet_transaction_no_update_delete
    BEFORE UPDATE OR DELETE ON wallet_transaction
    FOR EACH ROW EXECUTE FUNCTION block_changes();

-- Stripe may deliver the same webhook more than once; we process each event id exactly once.
CREATE TABLE processed_webhook_event (
    event_id    VARCHAR(255) PRIMARY KEY,
    type        VARCHAR(64)  NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_provider_reference ON payment (provider_reference);
