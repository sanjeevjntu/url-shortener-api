# Decisions (ADR log)

Each decision: context, choice, rejected alternatives, consequences. All **Accepted**.

## D1. Plain JDBC (`JdbcClient`), not JPA
The important SQL is hand-written: `INSERT … ON CONFLICT`, upserts, CHECK constraints; the inherited prototype's JPA hid
a defect (`save()` on an assigned ID became `merge`). **Choice:** `JdbcClient`, explicit SQL, immutable records, Flyway.
**Rejected:** Spring Data JPA / JDBC (same `isNew` trap, no upserts). **Cost:** more mapping code, offset by tests on real
PostgreSQL.

## D2. PostgreSQL + in-process Caffeine; no Redis, no Kafka
One node, ~1k redirects/s. **Choice:** Postgres for all state; Caffeine for the redirect cache and rate-limit buckets;
analytics through an in-memory bounded queue. Redis would add a network hop to every redirect and a container to run.
**Cost:** per-instance cache and limits: another instance may serve a deleted link for up to the cache TTL (5 min), and N
instances allow N × the rate limit. **Revisit when** scaling out: Caffeine stays as a short-TTL L1; Redis adds shared
limits (Bucket4j has a Redis backend) and delete invalidation via pub/sub.

## D3. Codes: one `SecureRandom` draw + fixed-width Base58 (8 chars) + database uniqueness
`nextLong(58^8)`, then `Base58.encode(n, 8)`: uniform, unguessable, no look-alike characters, 1.28e14 codes.
`UNIQUE(code)` decides; up to 5 redraws, then `503`. **Rejected:** per-character draws (8 RNG calls, no upgrade path);
`randomByte % 58` (~25% skew, measured); sequence + Base62 (enumerable); URL-hash truncation (collisions, leaks the
target); UUID (too long); `ThreadLocalRandom` (predictable). **Scale-out path:** sequence → keyed Feistel permutation →
same encoder: collision-free, no retries.

## D4. `302` redirects; analytics off the request path
`302` so browsers don't cache the hop and every click is counted. Clicks go to a bounded queue; a 1 s flusher aggregates
per (code, day, dimension) and upserts. Full queue → drop and count, never block; `mode=sync` is the baseline and
rollback. **Cost:** analytics approximate under overload; up to 1 s of clicks lost on a crash. Privacy: no IPs, no full
referrer URLs, client geo headers ignored.

## D5. URL validation without DNS
http/https only, ≤ 4096 chars; host lowercased, IDN → ASCII, default port and userinfo dropped, dot segments resolved;
**encoded bytes, query and fragment preserved**. Rejected: localhost-style, `.internal`/`.local` and single-label hosts;
loopback, private, link-local, CGNAT, unique-local and metadata IPs; IPv4-mapped IPv6; numeric hosts (`2130706433`,
`0x7f.1`, `127.1`). **Known gap:** a public hostname resolving to a private IP passes. The service never fetches
targets, so this is redirect hygiene, not SSRF.

## D6. API keys: named, scoped, hash-only
Creating links, deleting them and reading stats need `X-API-Key`; redirects and link lookups stay public (whoever clicks
a link has no key, and a lookup shows no more than the redirect). Create was public at first and was **closed after
review**: open shorteners are abused to disguise spam and phishing, a per-IP limit is bypassed with many IPs, and an
anonymous creator can be neither audited nor cut off. OAuth2 + Keycloak was **rejected by the engineer** (too heavy for
a reviewer to run). **Choice:** `shortener.api-keys: [{name, sha256, scopes, expiresAt?}]`, configured from the
environment or a secret store; **no key is committed**. Scopes `links:create`, `links:delete`, `stats:read` (`403`
without); optional expiry (`401` after, warned 14 days ahead); constant-time comparison; failures logged, counted,
throttled; the caller stored in `links.created_by` / `deleted_by`. SHA-256, not bcrypt: keys are random 40-character strings, not low-entropy passwords.
**No key configured:** startup fails, except in the `local` profile, which generates a key per run and logs it once
(like Spring Security's default password). `local` is the default only from a checkout (`config/application.yml`, read
from the working directory) and in Docker Compose; the image has no `config/` and an explicit profile wins, so
deployments never fall back to it.
**Rejected:** a plaintext key in properties (leaks with the config); Jasypt-encrypted properties (the master password
just moves the secret). **Limits:** keys identify integrations, not end users (no per-user ownership); the `local` key is in the log. **Upgrade path:** OAuth2
resource server.

## D7. No URL deduplication; no custom aliases
The same URL twice gives two codes: deduplication couples callers (one delete breaks everyone's link) and costs a
lookup. Retries are safe with `Idempotency-Key`: scoped per API key (not per IP, which NAT shares and phones
change), ≤ 128 chars, 24 h; same body → replay, different body →
`409`, link since deleted or expired → `410`. The client generates it (a UUID per logical create); a server-issued key
would be lost exactly when needed, after a timeout. Custom aliases were **removed**: not in the brief, guessable, and
they needed reserved words and conflict rules.

## D8. Rate limiting: Bucket4j token buckets per client
| Policy | Burst | Refill | Consumed by |
|---|---|---|---|
| `create` | 10 | 10/min | `POST /api/v1/links`, **per API key** |
| `redirect` | 200 | 6000/min | `GET /{code}` |
| `auth-failure` | 10 | 5/min | failed API-key attempts only |

Creates are limited per API key, so a leaked key used from many IPs stays capped. Redirects and failed auth are
anonymous, so they are limited per TCP peer: IPv4 address or IPv6 **/64** (otherwise rotating addresses bypass the
limit). Authentication runs before the create limit, so unauthenticated floods only drain the auth-failure budget. Buckets live in a
bounded Caffeine map, so random-IP floods cannot exhaust memory. `429` + `Retry-After`. `X-Forwarded-For` counts only when
the deployment enables forwarded headers for its own load balancer. **Rejected:** Resilience4j `RateLimiter` (one global
limit per name, no per-client keys); the earlier hand-written bucket and IP resolver; fixed windows (2× bursts at
boundaries). **Cost:** per-instance enforcement.

## D9. Expiry: lazy evaluation; "private links" deferred
`expiresAt` is checked on every read (cached links included) and answered with `410`. The background sweeper was
**removed**: it only rewrote `status` and cost an index (dropped in V3). Private links are deferred: codes are already
unguessable, and access control needs user identity the system does not have. **Rejected for now:** click-count limits
(a write on the hot path), password links, code reuse after expiry (link hijacking).

## D10. Delivery orchestration: a governing control plane, outside the app
The brief asks for an orchestration layer over the whole lifecycle: gates, approvals, retries, rollback, an audit trail,
reliability metrics, re-planning. **Choice:** a small control plane in `orchestration/` (Python standard library only): a
JSON stage graph; gates that run the project's own commands; approvals read from `docs/SIGNOFF.md`; fingerprint-based
re-planning; a hash-chained ledger; metrics computed from the ledger. It **governs** the agent's work and does not drive
the AI: the coding session does the work, the engine decides what may proceed. Every gate is checkable evidence (a test
run, a file hash), not a self-report; a reviewer can run `plan` with no build tools; nothing is added to the production
image. **Rejected:** an agent framework (LangGraph, Temporal): heavy dependencies, and the value is the governance rules,
not the runtime; CI alone (YAML jobs have no human-approval state, no re-plan and no ledger); an engine that calls an
LLM (non-deterministic gates, secrets in the pipeline, nothing a reviewer can rerun). **Cost:** the AI steps are recorded
through the [AI log](AI-LOG.md), not launched by the engine; a sign-off is a name in a Markdown table, so identity rests
on git history and review; one machine, one process.
