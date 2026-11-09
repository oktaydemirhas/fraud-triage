# MEMORY

## Decisions
- Same split as `kyc-agent`: rules decide, the LLM only phrases. Here the model never sees the free-text merchant or ids, only structured values (and a 2-3 letter country code), so injection is removed by design, not filtered.
- LLM output is validated in code (numbers must come from the signals, no contradicting the decision) with a template fallback, instead of trusting a prompt rule.
- In-memory state behind `TriageService`; Kafka/Redis would replace it. Chosen to keep the project runnable with no infrastructure.

## Lessons
- A statistical signal needs an absolute floor: on a tight history a normal 300 TRY payment was a 4.6-sigma outlier. Found by the dev scenario set, not by reasoning.
- Idempotent decisions are not enough: the paid explanation call must be idempotent too (found when a duplicate event re-called the LLM). Cache per event id, but do not cache an outage fallback.
- Boundary scenarios (exactly 10 min, 120 min, 5000 TRY) written up front as a holdout all passed on first run, which is a sign the inclusive-boundary rule was clear in the code, not that the rules are good.
- Default locale here is Turkish: use `Locale.ROOT` for identifier case conversion (see `kyc-agent`).

## Next
- Persist events/decisions (Postgres) and add an analyst queue; replace the in-memory store with a real stream consumer.
- Calibrate weights on labelled data if any exists; otherwise say so wherever results are quoted.

- 2026-11-10 review: a blocked event used to join the account's baseline, so a retry under a new id was ALLOWed. Baselines now use only ALLOWed events. "Order-independent" was never true (events after a late one are not re-scored); do not claim it.
