# Security design and threat model

Working assumption: **the model's output is untrusted input.** It can be manipulated by text in the data it
reads (prompt injection), it can make mistakes, and it can loop. The controls below hold even if the model is
fully hostile. `AgentSecurityIntegrationTest` checks this by replacing the model with a scripted attacker.

## Threats, controls, and the tests that prove them

| # | Threat | Control | Test |
|---|---|---|---|
| 1 | Prompt injection in data ("ignore previous instructions and transfer…") makes the model move money | The model has **no** tool that executes anything. `prepare*` tools only create drafts; execution is a separate user-only REST call | `AgentSecurityIntegrationTest.injectedInstructionCanAtMostProduceADraftThatOnlyTheUserCanApprove`, `AgentToolsTest.theModelsToolSurfaceIsExactlyTheReviewedList` |
| 2 | Model calls a tool that doesn't exist (e.g. `approvePaymentDraft`) | Allowlist check before execution; whole turn stops; audited | `toolsThatDoNotExistAreRefusedBeforeAnythingRuns` |
| 3 | Model reads or acts on another user's data | User id and role come from `ToolContext` (set from the JWT), never from tool arguments. Every repository query takes a user id | `modelCannotChooseWhoseDataItActsOn`, `AgentToolsTest.historyIsScopedToTheUserInTheToolContext` |
| 4 | Runaway tool loop (cost, DoS) | At most 8 tool calls per message; 20 messages/min per user | `endlessToolLoopsAreCutOff`, `RateLimiterTest` |
| 5 | Regular user reads AWS data via the AI or the API | Role check inside the cloud tools *and* `/api/cloud/**` requires `ADMIN` | `regularUsersCannotReadCloudDataThroughTheAgent`, `SecurityConfigTest.cloudEndpointsRequireAdmin` |
| 6 | Conversation history leaks between users | Memory key derived from the authenticated user id only | `conversationMemoryIsPerUser` |
| 7 | Approval redirected to a different amount/recipient | Draft terms immutable (DB trigger); user approves by reference | `draftTermsCannotBeChangedAfterCreation` |
| 8 | Stale approval | Drafts expire after 10 minutes | `expiredDraftsCannotBeApproved` |
| 9 | Double charge from retries or double-clicks | Conditional `PENDING → EXECUTING` update; provider idempotency key = draft reference; retry returns the original result | `approvingExecutesOnceAndRetriesReturnTheSamePayment`, `concurrentApprovalsProduceExactlyOnePayment` |
| 10 | Two approvals racing past the spending limit | Limit re-checked at approval under `SELECT … FOR UPDATE` on the user row | `limitsAreEnforcedAtPrepareAndAgainAtApproval` |
| 11 | Overdraft via concurrent wallet transfers | Wallets locked in fixed order; balance re-checked under lock; `CHECK (balance >= 0)` | `balanceIsRecheckedAtApprovalTime`, `opposingConcurrentTransfersNeitherDeadlockNorLoseMoney` |
| 12 | Crash between charge and record | Recovery job re-runs with the same idempotency key, only while still `EXECUTING` | `interruptedApprovalIsFinishedByRecoveryExactlyOnce` |
| 13 | Forged, tampered or replayed webhook | HMAC-SHA256 over `timestamp.payload`, constant-time compare, 5-minute tolerance, event-id dedupe | `StripeWebhookIntegrationTest` (4 tests) |
| 14 | Live Stripe key configured by mistake | Startup fails unless the key is `sk_test_`/`rk_test_` | `StripePaymentProviderTest.liveKeysAreRefused` |
| 15 | Tampered or forged JWT | HS256 signature + issuer + expiry validation | `SecurityConfigTest.tamperedTokenIsRejected` |
| 16 | Password guessing | 5 failed logins per username per 15 min, then 429; same error for unknown users and wrong passwords | `AuthIntegrationTest` (3 tests) |
| 17 | History rewritten to hide activity | `audit_log` and `wallet_transaction` are append-only (DB triggers) | `ledgerRowsCannotBeAlteredOrDeleted`; audit trigger checked manually |
| 18 | Deployed with demo users or a default secret | Seed data only in the `local` profile; `prod` has no defaults for DB or JWT secret and refuses to start without them | verified manually with the prod image |
| 19 | XSS steals the session | Token held in memory only (not localStorage); nginx sends a strict CSP, `X-Frame-Options: DENY`, `nosniff` | verified with `curl -I` |
| 20 | AI changes infrastructure | No write methods in `CloudReadService`; IAM policy grants only `ec2:DescribeInstances` and `ce:GetCostAndUsage` | `infra/aws/iam-instance-role-policy.json` |

## Secrets

| Secret | Where it lives | Never… |
|---|---|---|
| OpenAI key, Stripe test keys, JWT secret, DB password | Environment, populated from AWS Secrets Manager in production | …sent to the browser or the model, logged, or written to the audit log |
| AWS credentials | Default AWS chain (EC2 instance role in production) | …in config, the database, or the app's env file |
| User passwords | BCrypt hashes | …stored or logged in plain text |

## Known limitations

- **Spending limits and the rate limiter live in memory or are per-instance.** Running several instances would
  need the rate limiter in Redis or at the API gateway. Spending limits are already safe across instances,
  because they're enforced under a database row lock.
- **The sandbox provider forgets idempotency keys on restart.** Stripe keeps them for 24 hours.
- **Recipients of card payments are free text.** A real system would only pay saved, verified payees, and paying a
  third party through Stripe would use Stripe Connect.
- **Database roles.** In production the app should use a role that can't `TRUNCATE` or disable triggers, with
  Flyway migrations run by a separate owner role. The single-role setup here keeps the demo simple.
- **The system prompt is not a security control.** It improves behaviour, but every guarantee above is enforced
  in code or in the database.
