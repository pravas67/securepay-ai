import { useCallback, useState } from 'react';
import type { Me } from '../api/client';
import ChatPanel from '../components/ChatPanel';
import WalletCard from '../components/WalletCard';
import PaymentsTable from '../components/PaymentsTable';
import AuditPanel from '../components/AuditPanel';
import PendingApprovals from '../components/PendingApprovals';
import CloudPanel from '../components/CloudPanel';

export default function DashboardPage({ me, onLogout }: { me: Me; onLogout: () => void }) {
  // Bumped after anything that can change server state (agent reply, approval), so panels refetch.
  const [version, setVersion] = useState(0);
  const refresh = useCallback(() => setVersion((v) => v + 1), []);

  return (
    <div className="layout">
      <header className="topbar">
        <strong>SecurePay AI</strong>
        <span className="badge">Sandbox · no real money</span>
        <span className="spacer" />
        <span className="muted">
          Signed in as {me.username}
          {me.role === 'ADMIN' && ' (admin)'}
        </span>
        <button className="secondary" onClick={onLogout}>Sign out</button>
      </header>
      <main className="grid">
        <ChatPanel onReply={refresh} isAdmin={me.role === 'ADMIN'} />
        <aside className="side">
          <PendingApprovals version={version} onDecided={refresh} />
          <WalletCard version={version} />
          <AuditPanel version={version} />
        </aside>
        <PaymentsTable version={version} />
        {me.role === 'ADMIN' && <CloudPanel />}
      </main>
    </div>
  );
}
