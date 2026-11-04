# Requirements (the pipeline's first input)

The assignment: *build a URL shortener from scratch with core APIs, analytics and reliability features, then improve it
(brownfield) and handle ambiguous requirements.* This file is the **normalised** form. The orchestrator fingerprints it,
so **editing any line re-plans every stage that depends on it** (see [README](README.md#re-planning)).

Assumptions and ambiguities: [ARCHITECTURE §1](../docs/ARCHITECTURE.md#1-problem-and-assumptions).

| ID | Requirement | Acceptance (summary) | Decision |
|---|---|---|---|
| R1 | Create a short link for a URL | Valid http(s) URL only; unique 8-character code; `201` | D3, D5 |
| R2 | Redirect a short code to its URL | `302`; unknown `404`; deleted or expired `410`; fast path served from cache | D2, D4 |
| R3 | Click analytics | Totals, per day, referrer host, browser; recording never slows or fails a redirect | D4 |
| R4 | Reliability | Redirects survive analytics and database failures; failures are bounded and observable | D2, D4 |
| R5 | Abuse protection | Per-key and per-IP rate limits; internal hosts rejected; failed-key throttle | D5, D8 |
| R6 | Expiry (ambiguous) | Optional absolute UTC `expiresAt`; expired answers `410`; a code is never reused | D9 |
| R7 | Private links (ambiguous) | Deferred: needs user identity, out of scope | D9 |
| R8 | Access control | API keys with scopes `links:create`, `links:delete`, `stats:read`; hashes only | D6 |
| R9 | Safe retries | Optional `Idempotency-Key`; per API client; replay returns the original | D6 |
| R10 | Safe change (brownfield) | Applied migrations are immutable; schema changes are additive; every change has a test | D1 |
