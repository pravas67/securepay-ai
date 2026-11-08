# SecurePay AI

An AI assistant for payments and cloud operations, built so that **the AI can only ask; the backend decides.**

Ask in plain language: *"Analyse my payments and flag anything unusual"*, *"Prepare a ₹500 test payment"*,
*"Transfer ₹250 to bob"*, *"What's our AWS bill this month?"*. The model picks from a small, reviewed set of
tools. Spring Boot runs them against data scoped to the signed-in user, and anything that moves money becomes
a **draft that only the human can approve**. Every step lands in an append-only audit log.

> Sandbox only. Card payments use an in-memory sandbox or Stripe **test mode** (live keys are refused at
> startup), and the wallet is simulated. Nothing here can move real money.

**Stack:** Java 21 · Spring Boot 3.5.16 · Spring AI 1.1.8 (OpenAI) · PostgreSQL 16 · Flyway · React 18 +
TypeScript + Vite · Docker · GitHub Actions · AWS (EC2, RDS, Secrets Manager, CloudWatch, IAM)

## What it does

| Capability | AI tool | Who can run it |
|---|---|---|
| Payment history and status | `getPaymentHistory`, `getPaymentStatus` | Any user, own data only |
| Anomaly detection (large, new recipient, duplicate, failed, prompt-injection text) | `analyzeTransactions` | Any user |
| Demo wallet balance and ledger | `getWalletBalance` | Any user |
| Prepare a card payment (sandbox / Stripe test) | `preparePayment` | Any user, **draft only** |
| Prepare a wallet-to-wallet transfer | `prepareWalletTransfer` | Any user, **draft only** |
| **Approve / execute** a draft | *none, by design* | Only the user, via the UI / REST |
| EC2 instance status, month-to-date AWS cost | `getAwsResourceStatus`, `getAwsMonthlyCost` | Admins only, read-only |

## Run it locally

Prerequisites: Java 21+, Maven 3.9+, Node 20+, Docker Desktop.

```bash
cp .env.example .env               # add OPENAI_API_KEY (everything except chat works without it)
```

**Option A: everything in containers** (http://localhost:3000)

```bash
docker compose -f infra/docker-compose.yml --profile app up -d --build
```

**Option B: run the apps yourself** (hot reload; http://localhost:5173)

```bash
docker compose -f infra/docker-compose.yml up -d --wait     # PostgreSQL on host port 5433
cd backend && set -a && source ../.env && set +a && mvn spring-boot:run
cd frontend && npm install && npm run dev                   # in a second terminal
```

Sign in as **alice** / `password` (admin, ₹25,000 demo wallet) or **bob** / `password` (regular user).
API docs: http://localhost:8080/swagger-ui.html (option B).

Reset all data: `docker compose -f infra/docker-compose.yml down -v`

### Optional integrations

| Integration | Enable with | Notes |
|---|---|---|
| Stripe test mode | `PAYMENT_PROVIDER=stripe`, `STRIPE_SECRET_KEY=sk_test_…`, `STRIPE_WEBHOOK_SECRET=whsec_…` | Locally: `stripe listen --forward-to localhost:8080/api/webhooks/stripe` prints the `whsec_…`. If your Stripe account can't charge INR, add its currency to `securepay.payments.supported-currencies` |
| AWS (read-only) | `AWS_ENABLED=true`, `AWS_REGION=…` | Uses your AWS CLI profile locally; grant only [the read-only policy](infra/aws/iam-instance-role-policy.json). Cost Explorer bills $0.01 per request |

## Tests

```bash
cd backend && mvn test     # needs Docker running: integration tests start their own PostgreSQL
```

50 tests across 12 classes. They include a **hostile-model suite**
([AgentSecurityIntegrationTest](backend/src/test/java/com/securepay/agent/AgentSecurityIntegrationTest.java)) that
replaces OpenAI with a scripted attacker. It tries prompt injection, unknown tools, smuggled user ids, endless
tool loops and cross-user memory, against the real services and database. Other suites cover concurrency
(10 simultaneous approvals give one payment; opposite wallet transfers don't deadlock), crash recovery, spending
limits, Stripe webhook signatures and replays, JWT tampering, login lockout and RBAC.
CI runs all of it on every push ([.github/workflows/ci.yml](.github/workflows/ci.yml)).

## How it's built

```
React ──JWT──▶ Spring Security ──▶ AgentService ──(allowlist, ≤8 calls, identity via ToolContext)──▶ AgentTools
                                                                                    │ read / prepare only
                     user clicks Approve ──▶ PaymentDraftService.approve ──▶ Sandbox | Stripe test | wallet ledger
                                                       │
                                         PostgreSQL (row locks, conditional updates, immutability triggers)
```

The key decisions:

1. **No execute tool.** Even a fully successful prompt injection can at most create a draft that the user sees
   and rejects.
2. **Identity never comes from the model.** User id and role reach tools through `ToolContext`, set from the JWT.
3. **We run the tool loop**, so unknown tools are refused and calls per message are capped.
4. **Exactly-once execution:** a conditional `PENDING → EXECUTING` update, provider idempotency key = draft
   reference, and a recovery job for crashes mid-payment.
5. **The database enforces the invariants:** immutable draft terms, append-only audit log and ledger, no
   negative balances.

Full details, with diagrams: [docs/architecture.md](docs/architecture.md) ·
threat model mapped to tests: [docs/security.md](docs/security.md).

## Documentation

| Doc | For |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Components, request and approval sequences, data model (Mermaid diagrams) |
| [docs/security.md](docs/security.md) | 20 threats → controls → the test that proves each |
| [docs/api.md](docs/api.md) | Endpoints, errors, curl examples |
| [docs/deployment-aws.md](docs/deployment-aws.md) | EC2 + RDS + Secrets Manager + ALB + CloudWatch, step by step |
| [docs/demo-script.md](docs/demo-script.md) | 10-minute demo and interview walkthrough |

## Project layout

```
backend/            Spring Boot API (Dockerfile included)
  src/main/java/com/securepay/
    agent/          Tool loop, memory, rate limit; AgentTools = the model's entire capability surface
    payment/        Drafts (prepare/approve/reject/recover), analyzer, Stripe webhook
      provider/     Sandbox and Stripe test-mode providers, webhook signature verifier
    wallet/         Ledger and transfers
    cloud/          Read-only EC2 and Cost Explorer
    security/       JWT, login, RBAC
    audit/ user/ config/ common/
  src/main/resources/db/
    migration/      Schema (all environments)
    seed/           Demo users and data (local profile only)
frontend/           React SPA, served by nginx with a strict CSP in containers
infra/
  docker-compose.yml          Local database, or the full stack with --profile app
  aws/                        Production compose, EC2 user data, IAM policy, secret template
docs/
.github/workflows/ci.yml
```

## Known limitations

See [docs/security.md](docs/security.md#known-limitations). In short:
- The chat rate limiter and chat memory are per-instance (use Redis to run several instances).
- Card-payment recipients are free text.
- The database uses a single role.
