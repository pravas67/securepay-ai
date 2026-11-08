import { useEffect, useState } from 'react';
import { approveDraft, getPendingDrafts, rejectDraft, type Draft } from '../api/client';
import { formatMoney } from '../format';

function useNow(intervalMs: number) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return now;
}

function remaining(expiresAt: string, now: number) {
  const seconds = Math.max(0, Math.floor((new Date(expiresAt).getTime() - now) / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

/**
 * The human approval step. The user sees the exact terms that will be executed;
 * the backend guarantees they can't change after the draft was created.
 */
export default function PendingApprovals({ version, onDecided }: { version: number; onDecided: () => void }) {
  const [drafts, setDrafts] = useState<Draft[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);
  const now = useNow(1000);

  useEffect(() => {
    getPendingDrafts().then(setDrafts).catch(() => setDrafts([]));
  }, [version]);

  async function decide(draft: Draft, action: 'approve' | 'reject') {
    setBusy(draft.reference);
    setMessage(null);
    try {
      if (action === 'approve') {
        const result = await approveDraft(draft.reference);
        const verb = result.status === 'PENDING' ? 'Submitted' : 'Sent';
        setMessage({ kind: 'ok', text: `${verb} ${formatMoney(result.amount, result.currency)} to ${result.recipient} (${result.reference}).` });
      } else {
        await rejectDraft(draft.reference);
        setMessage({ kind: 'ok', text: `Rejected ${draft.reference}.` });
      }
    } catch (err) {
      setMessage({ kind: 'error', text: (err as Error).message });
    } finally {
      setBusy(null);
      onDecided();
    }
  }

  const live = drafts.filter((d) => new Date(d.expiresAt).getTime() > now);
  if (live.length === 0 && !message) return null;

  return (
    <section className="card approvals">
      <h2>Pending approvals</h2>
      {message && <p className={message.kind === 'error' ? 'error' : 'muted'}>{message.text}</p>}
      {live.map((d) => (
        <div key={d.reference} className="draft">
          <span className="kind">{d.kind === 'WALLET_TRANSFER' ? 'Demo wallet transfer' : 'Card payment (sandbox)'}</span>
          <span className="amount">{formatMoney(d.amount, d.currency)}</span>
          <dl>
            <dt>To</dt>
            <dd>{d.recipient}</dd>
            <dt>Purpose</dt>
            <dd>{d.description ?? '—'}</dd>
            <dt>Reference</dt>
            <dd><code>{d.reference}</code></dd>
            <dt>Expires in</dt>
            <dd>{remaining(d.expiresAt, now)}</dd>
          </dl>
          <div className="actions">
            <button onClick={() => decide(d, 'approve')} disabled={busy !== null}>
              {busy === d.reference ? 'Working…' : 'Approve & send'}
            </button>
            <button className="secondary" onClick={() => decide(d, 'reject')} disabled={busy !== null}>Reject</button>
          </div>
        </div>
      ))}
    </section>
  );
}
