# API reference

Interactive docs: run the backend locally and open **http://localhost:8080/swagger-ui.html**. Click
*Authorize* and paste a token from `/api/auth/login`. Swagger is disabled in the `prod` profile.

Every endpoint except login, health and the Stripe webhook needs `Authorization: Bearer <token>`.
Errors always look like `{"error": "human-readable message"}`.

| Status | Meaning |
|---|---|
| 401 | Missing, invalid or expired token; or wrong username/password at login |
| 403 | Authenticated, but not allowed (e.g. a non-admin calling `/api/cloud/**`) |
| 404 | Doesn't exist **or belongs to someone else** (deliberately indistinguishable) |
| 409 | Wrong state (draft expired, already rejected, still executing) |
| 422 | Breaks a payment rule (limit, currency, balance, declined by the provider) |
| 429 | Rate limited (chat: 20/min; login: 5 failures per 15 min per username) |
| 503 | AI not configured or provider unavailable |

## Auth

```bash
curl -s -H 'Content-Type: application/json' -d '{"username":"alice","password":"password"}' \
  localhost:8080/api/auth/login
# {"token":"eyJ…","expiresAt":"…","user":{"id":1,"username":"alice","role":"ADMIN"}}

TOKEN=$(curl -s -H 'Content-Type: application/json' -d '{"username":"alice","password":"password"}' \
  localhost:8080/api/auth/login | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
AUTH="Authorization: Bearer $TOKEN"
```

| Method | Path | Notes |
|---|---|---|
| POST | `/api/auth/login` | `{username, password}` → 30-minute token |
| GET | `/api/me` | `{id, username, role}` |

## Agent

| Method | Path | Notes |
|---|---|---|
| POST | `/api/agent/chat` | `{message}` (≤ 2000 chars) → `{reply, toolsUsed[]}`. Remembers the last 20 messages per user |
| DELETE | `/api/agent/conversation` | Forget the conversation (204) |

```bash
curl -s -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"message":"Analyse my payments from the last 60 days"}' localhost:8080/api/agent/chat
```

## Payments and wallet (read)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/payments?days=30` | Your payments, newest first (1–90 days) |
| GET | `/api/payments/{reference}` | One of your payments |
| GET | `/api/wallet` | Demo wallet balance |
| GET | `/api/wallet/transactions` | Last 20 ledger lines (negative = sent) |
| GET | `/api/audit` | Your last 50 audit entries |

## Drafts and approval (write)

| Method | Path | Body | Notes |
|---|---|---|---|
| GET | `/api/payment-drafts` | | Your pending, unexpired drafts |
| POST | `/api/payment-drafts` | `{amount, currency, recipient, description?}` | Draft a card payment (201) |
| POST | `/api/payment-drafts/wallet-transfers` | `{amount, recipient, description?}` | Draft a wallet transfer to a username (201) |
| POST | `/api/payment-drafts/{ref}/approve` | | **Executes** it. Retry-safe: returns the same result |
| POST | `/api/payment-drafts/{ref}/reject` | | |

```bash
REF=$(curl -s -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"amount":250,"recipient":"bob","description":"Lunch"}' \
  localhost:8080/api/payment-drafts/wallet-transfers | python3 -c 'import sys,json;print(json.load(sys.stdin)["reference"])')
curl -s -H "$AUTH" -X POST localhost:8080/api/payment-drafts/$REF/approve
# {"kind":"WALLET_TRANSFER","draftReference":"DRF-…","reference":"TRF-…","amount":250.00,…,"status":"SUCCEEDED"}
```

Rules (configurable in `application.yml` under `securepay.payments`): ≤ ₹10,000 per transaction, ≤ ₹20,000
per rolling 24 hours (card payments and wallet transfers combined), INR only, at most 5 pending drafts,
drafts expire after 10 minutes. With the sandbox provider, a recipient containing "decline" is declined.

## Cloud (admin only, read-only)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/cloud/instances` | Up to 50 EC2 instances in `AWS_REGION` |
| GET | `/api/cloud/costs` | Month-to-date cost, top 10 services. **Each call costs $0.01 at AWS** |

Both return `{"enabled": false, …}` unless `AWS_ENABLED=true`.

## Webhook

| Method | Path | Notes |
|---|---|---|
| POST | `/api/webhooks/stripe` | Only when `PAYMENT_PROVIDER=stripe`. Requires a valid `Stripe-Signature` header |
