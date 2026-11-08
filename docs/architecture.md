# Architecture

SecurePay AI lets a user ask for financial and cloud operations in plain language. The core design rule:
**the model can ask, but only backend code decides and acts.** Everything below follows from that.

## Components

```mermaid
flowchart LR
    U[User] --> FE[React SPA<br/>nginx in production]
    FE -->|/api + Bearer JWT| SEC[Spring Security<br/>JWT, RBAC, rate limits]
    SEC --> AG[AgentService<br/>explicit tool loop]
    SEC --> DR[PaymentDraftController<br/>approve / reject]
    AG <-->|prompt + tool schemas| LLM[(OpenAI)]
    AG -->|allowlisted calls,<br/>identity via ToolContext| TOOLS[AgentTools]
    TOOLS --> PS[PaymentService / TransactionAnalyzer]
    TOOLS --> WS[WalletService]
    TOOLS --> DS[PaymentDraftService<br/>prepare only]
    TOOLS --> CS[CloudReadService<br/>admin only]
    DR --> DS2[PaymentDraftService<br/>approve = execute]
    DS2 --> PP[PaymentProvider<br/>Sandbox or Stripe test]
    DS2 --> WS
    CS --> AWS[(AWS EC2 + Cost Explorer<br/>read-only IAM)]
    PP <--> STRIPE[(Stripe test mode)]
    STRIPE -->|signed webhook| WH[StripeWebhookController]
    PS & WS & DS & DS2 & WH --> DB[(PostgreSQL)]
    TOOLS & DS & DS2 & WH & SEC --> AUD[AuditService<br/>append-only]
    AUD --> DB
```

Two separate entry points reach the money-moving code:

| Path | Who triggers it | What it can do |
|---|---|---|
| `POST /api/agent/chat` → `AgentTools` | The model, on the user's behalf | Read; **prepare** drafts |
| `POST /api/payment-drafts/{ref}/approve` | Only the signed-in user, in the UI | **Execute** one specific draft |

The model has no tool that reaches the second path. A unit test pins the exact list of tools.

## Chat request

```mermaid
sequenceDiagram
    actor User
    participant API as AgentService
    participant LLM as OpenAI
    participant T as AgentTools
    participant DB as PostgreSQL

    User->>API: "Analyse my payments and flag anything odd"
    API->>API: rate limit (20/min/user), audit AGENT_REQUEST
    API->>API: load this user's last 20 messages (memory keyed by user id)
    API->>LLM: system prompt + history + message + tool schemas
    LLM-->>API: call analyzeTransactions(days=60)
    API->>API: tool on allowlist? total calls ≤ 8?
    API->>T: analyzeTransactions(60, ToolContext{userId, role})
    T->>DB: payments WHERE user_id = :sessionUser
    T-->>API: findings (rule-based), audit TOOL_analyzeTransactions
    API->>LLM: tool result
    LLM-->>API: plain-language explanation
    API-->>User: reply + list of tools used
```

The tool loop runs in our code (`internalToolExecutionEnabled(false)`), not inside the Spring AI model client.
That gives one place to:

- refuse any tool name that isn't registered, before it runs;
- cap tool calls per message (8), which stops runaway loops and runaway cost;
- report which tools were used to the UI and the audit log.

## Approval and execution

```mermaid
sequenceDiagram
    actor User
    participant AI as AgentTools
    participant S as PaymentDraftService
    participant DB as PostgreSQL
    participant P as PaymentProvider

    AI->>S: preparePayment(500, INR, "Test Recipient")
    S->>S: validate amount, currency, text, ≤5 pending, 24h limit (advisory)
    S->>DB: INSERT draft PENDING_APPROVAL, expires in 10 min (terms frozen by trigger)
    Note over User: Reviews exact amount, recipient, purpose in "Pending approvals"
    User->>S: POST /approve
    rect rgb(240,240,240)
    Note over S,DB: Tx 1 (short)
    S->>DB: SELECT … FOR UPDATE on user row
    S->>DB: re-check expiry and 24h limit
    S->>DB: UPDATE draft SET EXECUTING WHERE status = PENDING_APPROVAL
    end
    S->>P: charge(idempotencyKey = draft reference)
    rect rgb(240,240,240)
    Note over S,DB: Tx 2
    S->>DB: INSERT payment, draft EXECUTED (or FAILED)
    end
    S-->>User: result (a retry returns the same result)
```

Wallet transfers use the same claim step, then move funds in **one** transaction: both wallets locked in
user-id order (so opposite transfers can't deadlock), two ledger lines written, and the draft marked executed.

If the server dies between the provider call and Tx 2, the draft stays `EXECUTING`. `PaymentRecoveryJob`
retries it after two minutes with the same idempotency key, so the provider returns the original result
instead of charging again.

## Data model

```mermaid
erDiagram
    app_user ||--o{ payment : owns
    app_user ||--|| wallet : has
    app_user ||--o{ payment_draft : proposes
    app_user ||--o{ audit_log : "acts in"
    wallet ||--o{ wallet_transaction : "ledger lines"
    payment_draft |o--o| payment : "executes as"

    app_user { bigint id PK
               varchar username
               varchar password_hash
               varchar role }
    payment { varchar reference
              numeric amount
              varchar status
              varchar provider_reference }
    payment_draft { varchar reference
                    varchar kind
                    numeric amount
                    varchar recipient
                    varchar status
                    timestamptz expires_at }
    wallet { numeric balance "CHECK >= 0" }
    wallet_transaction { varchar reference
                         numeric amount "signed"
                         numeric balance_after }
    audit_log { varchar action
                varchar outcome }
    processed_webhook_event { varchar event_id PK }
```

Database-level guarantees (they hold even if application code has a bug):

| Rule | Mechanism |
|---|---|
| Audit log and wallet ledger are append-only | `BEFORE UPDATE OR DELETE` triggers |
| A draft's amount, currency, recipient, description, kind and owner never change | `payment_draft_freeze_terms` trigger |
| No negative wallet balance | `CHECK (balance >= 0)` |
| Each webhook event is processed once | primary key on `processed_webhook_event.event_id` |

## Packages

```
com.securepay
├── agent/     AgentService (tool loop, memory, rate limit), AgentTools (the model's whole capability surface)
├── payment/   PaymentDraftService (prepare/approve/reject/recover), TransactionAnalyzer, webhook
│   └── provider/  PaymentProvider, SandboxPaymentProvider, StripePaymentProvider, StripeSignatureVerifier
├── wallet/    WalletService (ledger transfers), WalletTransaction
├── cloud/     CloudReadService (EC2 + Cost Explorer, read-only)
├── security/  JWT issue/verify, SecurityConfig (RBAC), login with brute-force limit
├── audit/     AuditService (REQUIRES_NEW, so failures are recorded too)
├── user/      AppUser, UserBootstrap (first admin in a fresh deployment)
├── config/    OpenAPI
└── common/    errors, RateLimiter
```
