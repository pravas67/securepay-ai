import { useState, type FormEvent } from 'react';
import { resetConversation, sendChat } from '../api/client';

interface Message {
  role: 'user' | 'assistant' | 'error';
  text: string;
  tools?: string[];
}

const SUGGESTIONS = [
  'Analyse my payments from the last 60 days and flag anything unusual.',
  'Prepare a ₹500 test payment to Test Recipient for a sandbox test.',
  'Transfer ₹250 from my demo wallet to bob for lunch.',
];
const ADMIN_SUGGESTIONS = ['Which EC2 instances are running, and what is this month’s AWS cost so far?'];

export default function ChatPanel({ onReply, isAdmin }: { onReply: () => void; isAdmin: boolean }) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);

  async function send(text: string) {
    const message = text.trim();
    if (!message || busy) return;
    setMessages((m) => [...m, { role: 'user', text: message }]);
    setInput('');
    setBusy(true);
    try {
      const { reply, toolsUsed } = await sendChat(message);
      setMessages((m) => [...m, { role: 'assistant', text: reply, tools: toolsUsed }]);
    } catch (err) {
      setMessages((m) => [...m, { role: 'error', text: (err as Error).message }]);
    } finally {
      setBusy(false);
      onReply();
    }
  }

  async function newChat() {
    await resetConversation().catch(() => undefined);
    setMessages([]);
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    send(input);
  }

  const suggestions = isAdmin ? [...SUGGESTIONS, ...ADMIN_SUGGESTIONS] : SUGGESTIONS;

  return (
    <section className="card chat">
      <div className="card-head">
        <h2>Assistant</h2>
        {messages.length > 0 && (
          <button className="secondary small-button" onClick={newChat} disabled={busy}>New chat</button>
        )}
      </div>
      <div className="messages">
        {messages.length === 0 && (
          <div className="suggestions">
            <p className="muted small">
              The assistant can read and analyse your data and <strong>prepare</strong> payments. Only you can approve them.
            </p>
            {suggestions.map((s) => (
              <button key={s} className="secondary" onClick={() => send(s)}>{s}</button>
            ))}
          </div>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`bubble ${m.role}`}>
            {m.text}
            {m.tools && m.tools.length > 0 && <div className="tools">Used: {m.tools.join(', ')}</div>}
          </div>
        ))}
        {busy && <div className="bubble assistant muted">Thinking…</div>}
      </div>
      <form className="composer" onSubmit={submit}>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Ask about your payments, wallet or cloud…"
          maxLength={2000}
        />
        <button type="submit" disabled={busy || !input.trim()}>Send</button>
      </form>
    </section>
  );
}
