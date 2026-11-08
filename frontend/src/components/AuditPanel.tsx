import { useEffect, useState } from 'react';
import { getAudit, type AuditEntry } from '../api/client';
import { formatDate } from '../format';

export default function AuditPanel({ version }: { version: number }) {
  const [entries, setEntries] = useState<AuditEntry[]>([]);

  useEffect(() => {
    getAudit().then(setEntries).catch(() => setEntries([]));
  }, [version]);

  return (
    <section className="card">
      <h2>Audit log</h2>
      {entries.length === 0 && <p className="muted">Agent requests and tool calls appear here.</p>}
      <ul className="audit">
        {entries.slice(0, 12).map((e) => (
          <li key={e.id}>
            <span className={`outcome ${e.outcome.toLowerCase()}`}>{e.outcome}</span>
            <span className="action">{e.action}</span>
            <span className="muted small">{formatDate(e.createdAt)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
