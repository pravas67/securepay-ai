import { useState, type FormEvent } from 'react';
import { ApiError, login, type Me } from '../api/client';

export default function LoginPage({ onLogin, notice }: { onLogin: (me: Me) => void; notice: string | null }) {
  const [username, setUsername] = useState('alice');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onLogin(await login(username, password));
    } catch (err) {
      setError(err instanceof ApiError && err.status < 500 ? err.message : 'Could not reach the server.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login">
      <form className="card" onSubmit={submit}>
        <h1>SecurePay AI</h1>
        <p className="muted">
          Local demo: <code>alice</code> (admin) or <code>bob</code>, password <code>password</code>
        </p>
        {notice && <p className="notice">{notice}</p>}
        <label>
          Username
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
        </label>
        <label>
          Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
        </label>
        {error && <p className="error">{error}</p>}
        <button type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
    </main>
  );
}
