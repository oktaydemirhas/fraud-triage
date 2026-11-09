# Fraud Triage

Spring Boot 4 / Java 21 service that scores a stream of account events (payments, logins, address/password changes) and tells an analyst
what needs a look. Rules decide (`ALLOW` / `REVIEW` / `BLOCK`); the LLM only writes the short note for events that are not `ALLOW`.
All data is synthetic.

## How an event flows
1. `POST /api/events` with an event (`id`, `accountId`, `type`, `amount`, `country`, `deviceId`, `merchant`, `at`).
2. `TriageService` keeps a bounded per-account history (500 events / 30 days, 10 000 accounts), is **idempotent** (a repeated `id` returns the first result and is not counted twice) and timestamp-ordered (a late event is inserted by timestamp and judged only against events before it; events after it are **not** re-scored, so decisions can depend on arrival order, see Known limits). "Usual" device, amounts and home country are learned only from events that were themselves ALLOWed, so a blocked attempt cannot become the new normal.
3. `RiskEngine` computes signals over sliding windows; the score is the sum of their weights (cap 100): `<30` ALLOW, `30-69` REVIEW, `>=70` BLOCK.

   | Signal | Weight | Fires when |
   |---|---|---|
   | `VELOCITY` | 30 / 45 | 5 / 8 payments in 10 min |
   | `BRUTE_FORCE_LOGIN` | 50 / 25 | 5 failed logins in 10 min, then success / still failing |
   | `IMPOSSIBLE_TRAVEL` | 40 | earlier event from another country within 120 min |
   | `AMOUNT_ANOMALY` | 35 / 45 | payment >= 500 TRY and z-score >= 3 / 5 against the account's earlier payments (needs 5) |
   | `NEW_DEVICE_HIGH_AMOUNT` | 30 | payment >= 5000 TRY from a never-seen device |
   | `ACCOUNT_TAKEOVER_PATTERN` | 40 | payment >= 2000 TRY within 30 min of a password/address change |
   | `ACCOUNT_CHANGE_NEW_DEVICE` | 25 | password/address change from a never-seen device |
   | `FOREIGN_COUNTRY` | 15 | payment outside the account's usual country (needs 5 events) |
4. `Explainer` (only for REVIEW/BLOCK) asks the LLM for a 2-3 sentence note, then **checks it in code**:
   every number must come from the signals, it must not contradict the decision, it must be short. Otherwise a template built from the same signals is returned (`source: TEMPLATE`, with `rejectedBecause`).
   The prompt contains only structured values; the free-text `merchant` and ids are never sent. The only event string that can reach it is `country`, restricted to 2-3 letters.
   Notes are cached per event id (bounded), so a retrying client cannot trigger repeated paid calls.

## Run
```bash
export LLM_API_KEY=...            # optional: without it REVIEW/BLOCK events get the template note
# optional: LLM_BASE_URL (https, default https://api.deepseek.com), LLM_MODEL (default deepseek-chat)
./mvnw spring-boot:run            # Windows: .\mvnw.cmd ; needs JDK 21 (JAVA_HOME)
./mvnw test                       # 23 tests, no key needed (the real-LLM eval is skipped)
```

```bash
curl -X POST localhost:8080/api/events -H 'Content-Type: application/json' -d '{
  "id":"e1","accountId":"acc-1","type":"PAYMENT","amount":120.50,"country":"TR","deviceId":"D1","merchant":"Market","at":"2026-11-04T12:00:00Z"
}'
```
Limits: events more than 5 min in the future or older than 30 days get 422; invalid bodies 400; 120 requests/minute per IP (in memory).

## Verified
- Scenario eval (deterministic, replayed through the real service with a fixed clock): dev set 18/18 (`scenarios.json`), holdout 16/16 (`holdout.json`, boundary cases written before the first run: 4 vs 5 payments, exactly 10 / 120 / 30 minutes, 4999 vs 5000 TRY, too little history, missing country).
- The dev set found a real flaw: with a tight history (180-220 TRY) a normal 300 TRY payment was "4.6 sigma" and went to REVIEW. Fixed with a 500 TRY floor for the statistical signal.
- `ExplanationEvalTest` (real DeepSeek, run when `LLM_API_KEY` is set): 15 of 15 notes for the REVIEW/BLOCK scenarios passed the validator; 5 of 5 hostile merchant strings never reached a note.
- Real HTTP run: 5 payments in 5 minutes -> `REVIEW` with `VELOCITY`, LLM note accepted; future event -> 422; empty body -> 400.

## Security review (2026-11-10) and what changed
An independent read-only review found real problems; fixed here:
- **A blocked attempt taught the account that it was normal.** A 6000 TRY payment from a new device got BLOCK, the same payment under a new id got ALLOW. Baselines (devices, amount statistics, home country) now come only from ALLOWed events; counting signals (velocity, travel, takeover) still see everything. Covered by `blockedAttemptDoesNotBecomeThePartOfNormalBehaviour`.
- **Rate limit bypass and unbounded table.** Forwarding headers were trusted from any client and the limiter ran after the body was parsed. A new `IngressGuard` filter runs first: socket address unless `fraud.trust-forwarded-headers=true`, bounded table, 8 KB cap, `Content-Length` required.
- `country` is validated (2-3 letters) because it does reach the note prompt; `toUpperCase(Locale.ROOT)` (a Turkish default locale turned "it" into "İT"); the note cache key can no longer collide across accounts; the README no longer claims order independence.

Re-run after these changes: unit and scenario tests (23/23, dev 18/18 and holdout 16/16 still pass). The real-LLM note eval was not re-run.

## Known limits
- 15/15 accepted notes means the validator's fallback path is covered by unit tests, not exercised by real model output. The notes are correct but read stiffly ("VELOCITY sinyali", "ağırlık").
- Thresholds and weights are hand-set, not learned or calibrated on real data. The scenario sets check that the rules behave as designed, not that they catch real fraud.
- State is in memory and single-instance. The seam for Kafka plus a state store is `TriageService`; there is no persistence or analyst queue.
- Not order-independent: events that arrive late are scored against what had arrived before them and later events are not re-scored. A password reset arriving after the payment it should have flagged lets that payment through as REVIEW instead of BLOCK.
- No authentication on `POST /api/events`: anyone who can reach the port can plant events in any account, push out a real account's history, or cause false REVIEW/BLOCK. Put it behind service-to-service auth.
- Flagged events never become "normal": there is no analyst confirmation API yet, so a genuine new device keeps scoring as new until one exists.
- State is never evicted: after 10 000 accounts new accounts get 422 until restart, and 10 000 x 500 events is more than a default heap holds.
- Paid LLM notes have no global budget or circuit breaker, are generated on the request thread, and fallbacks are not cached.
- `deviceId` is not part of any evidence; `country` is the only string from the event that is shown or sent.
