import { useEffect, useState } from 'react';
import { getWallet, getWalletTransactions, type Wallet, type WalletTransaction } from '../api/client';
import { formatDate, formatMoney } from '../format';

export default function WalletCard({ version }: { version: number }) {
  const [wallet, setWallet] = useState<Wallet | null>(null);
  const [transactions, setTransactions] = useState<WalletTransaction[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getWallet().then(setWallet).catch((e: Error) => setError(e.message));
    getWalletTransactions().then(setTransactions).catch(() => setTransactions([]));
  }, [version]);

  return (
    <section className="card">
      <h2>Demo wallet</h2>
      {error && <p className="error">{error}</p>}
      {wallet && (
        <>
          <p className="balance">{formatMoney(wallet.balance, wallet.currency)}</p>
          <p className="muted small">Simulated balance, not real funds</p>
        </>
      )}
      {wallet && transactions.length > 0 && (
        <ul className="ledger">
          {transactions.slice(0, 5).map((t) => (
            <li key={`${t.reference}-${t.amount}`}>
              <span>{t.amount < 0 ? `To ${t.counterparty}` : `From ${t.counterparty}`}</span>
              <span className={`num ${t.amount < 0 ? 'debit' : 'credit'}`}>
                {t.amount > 0 ? '+' : ''}{formatMoney(t.amount, wallet.currency)}
              </span>
              <span className="muted small">{formatDate(t.createdAt)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
