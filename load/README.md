# Load test: sync vs async analytics (brownfield scenario C)

Measures redirect latency at a fixed request rate with analytics written **inside** the request (`sync`, the baseline)
and **off** the request path (`async`, the default).

**Prerequisites:** [k6](https://k6.io) and Postgres (`docker compose up -d postgres`). All requests come from one IP, so
raise the redirect limit for the test (environment variables bind to `shortener.*`):

```bash
export SHORTENER_RATELIMITS_REDIRECT_CAPACITY=1000000 SHORTENER_RATELIMITS_REDIRECT_REFILLPERMINUTE=100000000
```

```bash
SHORTENER_ANALYTICS_MODE=sync ./gradlew bootRun            # terminal 1: baseline
k6 run -e API_KEY=<key> -e MODE=sync -e RATE=500 load/redirect.js    # terminal 2; key from the app log

SHORTENER_ANALYTICS_MODE=async ./gradlew bootRun           # restart the app: refactored
k6 run -e API_KEY=<key> -e MODE=async -e RATE=500 load/redirect.js
```

Run each mode twice and keep the second run (JIT warm). Compare `http_req_duration{kind:redirect}` p95/p99 and check
<http://localhost:8081/actuator/metrics/shortener.analytics.dropped> (expected 0 at this rate).

## Results (engineer to fill in; also quote them in docs/SCENARIOS.md, 2C)

| Mode | Rate (rps) | p95 (ms) | p99 (ms) | Errors | Machine |
|---|---|---|---|---|---|
| sync | 500 | | | | |
| async | 500 | | | | |

One laptop, app and DB on the same machine, warm cache: the absolute numbers are not production numbers; the
**difference between the modes** is what this test shows.
