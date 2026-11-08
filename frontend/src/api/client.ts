// The access token lives only in memory: a page refresh signs you out. That's deliberate: nothing
// sensitive sits in localStorage where any injected script could read it. Tokens expire after 30 minutes.
let token: string | null = null;
let onUnauthorized: () => void = () => {};

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

export function clearToken() {
  token = null;
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (init.body) headers.set('Content-Type', 'application/json');

  const res = await fetch(path, { ...init, headers });
  if (res.status === 401 && token) {
    // Expired or revoked token: sign out instead of showing errors everywhere.
    clearToken();
    onUnauthorized();
  }
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new ApiError(res.status, body?.error ?? `Request failed (${res.status})`);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export interface Me {
  id: number;
  username: string;
  role: 'USER' | 'ADMIN';
}

export async function login(username: string, password: string): Promise<Me> {
  const res = await api<{ token: string; user: Me }>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  });
  token = res.token;
  return res.user;
}

export interface Payment {
  reference: string;
  amount: number;
  currency: string;
  recipient: string;
  description: string | null;
  status: string;
  createdAt: string;
}

export interface Wallet {
  balance: number;
  currency: string;
  simulated: boolean;
  updatedAt: string;
}

export interface WalletTransaction {
  reference: string;
  amount: number;
  balanceAfter: number;
  counterparty: string;
  description: string | null;
  createdAt: string;
}

export type DraftKind = 'PAYMENT' | 'WALLET_TRANSFER';

export interface Draft {
  reference: string;
  kind: DraftKind;
  amount: number;
  currency: string;
  recipient: string;
  description: string | null;
  status: string;
  createdAt: string;
  expiresAt: string;
}

export interface ApprovalResult {
  kind: DraftKind;
  draftReference: string;
  reference: string;
  amount: number;
  currency: string;
  recipient: string;
  status: string;
}

export interface AuditEntry {
  id: number;
  action: string;
  details: string | null;
  outcome: 'SUCCESS' | 'DENIED' | 'FAILURE';
  createdAt: string;
}

export interface AgentReply {
  reply: string;
  toolsUsed: string[];
}

export interface InstancesReport {
  enabled: boolean;
  error: string | null;
  instances: { id: string; name: string | null; type: string; state: string; availabilityZone: string | null }[];
}

export interface CostReport {
  enabled: boolean;
  error: string | null;
  period: string | null;
  total: number | null;
  unit: string | null;
  topServices: { service: string; amount: number; unit: string }[];
}

const draftPath = (reference: string, action: string) => `/api/payment-drafts/${encodeURIComponent(reference)}/${action}`;

export const getPayments = (days = 30) => api<Payment[]>(`/api/payments?days=${days}`);
export const getWallet = () => api<Wallet>('/api/wallet');
export const getWalletTransactions = () => api<WalletTransaction[]>('/api/wallet/transactions');
export const getAudit = () => api<AuditEntry[]>('/api/audit');
export const sendChat = (message: string) =>
  api<AgentReply>('/api/agent/chat', { method: 'POST', body: JSON.stringify({ message }) });
export const resetConversation = () => api<void>('/api/agent/conversation', { method: 'DELETE' });
export const getPendingDrafts = () => api<Draft[]>('/api/payment-drafts');
export const approveDraft = (reference: string) => api<ApprovalResult>(draftPath(reference, 'approve'), { method: 'POST' });
export const rejectDraft = (reference: string) => api<Draft>(draftPath(reference, 'reject'), { method: 'POST' });
export const getCloudInstances = () => api<InstancesReport>('/api/cloud/instances');
export const getCloudCosts = () => api<CostReport>('/api/cloud/costs');
