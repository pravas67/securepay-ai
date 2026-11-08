import { useEffect, useState } from 'react';
import { getPayments, type Payment } from '../api/client';
import { formatDate, formatMoney } from '../format';

export default function PaymentsTable({ version }: { version: number }) {
  const [payments, setPayments] = useState<Payment[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getPayments(60).then(setPayments).catch((e: Error) => setError(e.message));
  }, [version]);

  return (
    <section className="card payments">
      <h2>Payments (last 60 days)</h2>
      {error && <p className="error">{error}</p>}
      {payments && payments.length === 0 && <p className="muted">No payments yet.</p>}
      {payments && payments.length > 0 && (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Reference</th>
                <th>Date</th>
                <th>Recipient</th>
                <th>Description</th>
                <th className="num">Amount</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {payments.map((p) => (
                <tr key={p.reference}>
                  <td><code>{p.reference}</code></td>
                  <td>{formatDate(p.createdAt)}</td>
                  <td>{p.recipient}</td>
                  <td className="desc">{p.description}</td>
                  <td className="num">{formatMoney(p.amount, p.currency)}</td>
                  <td><span className={`status ${p.status.toLowerCase()}`}>{p.status}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
