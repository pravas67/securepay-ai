-- Local-only. Opening-balance ledger rows so that sum(ledger) == wallet balance from the start.
INSERT INTO wallet_transaction (reference, wallet_id, user_id, amount, balance_after, counterparty, description)
SELECT 'OPEN-' || w.user_id, w.id, w.user_id, w.balance, w.balance, 'Opening balance', 'Demo funds'
FROM wallet w;

-- alice is an administrator (can use the read-only AWS tools); bob stays a regular user.
UPDATE app_user SET role = 'ADMIN' WHERE username = 'alice';
