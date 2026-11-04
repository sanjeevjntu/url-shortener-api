#!/usr/bin/env bash
# End-to-end smoke test of a running instance: one command, curl only, about 10 seconds.
# Usage: scripts/smoke-test.sh <api-key> [base-url]
#   The key is printed in the app log at startup:  docker compose logs app | grep X-API-Key
#   (or in the IntelliJ / ./gradlew bootRun console). Exit code 0 = all checks passed.
set -u
KEY="${1:?usage: $0 <api-key> [base-url]   (find the key with: docker compose logs app | grep X-API-Key)}"
B="${2:-http://localhost:8080}"
J='Content-Type: application/json'
K="X-API-Key: $KEY"
pass=0; fail=0; limited=0

check() { # <description> <expected> <actual>
  if [ "$2" = "$3" ]; then echo "  ✓ $1"; pass=$((pass + 1))
  else echo "  ✗ $1 (expected $2, got $3)"; fail=$((fail + 1)); [ "$3" = "429" ] && limited=1; fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
new_uuid() { uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid; }

echo "Smoke test against $B"

echo "Create and redirect"
check "POST without key -> 401" 401 \
      "$(status -XPOST "$B/api/v1/links" -H "$J" -d '{"url":"https://spring.io/projects/spring-boot"}')"
out=$(curl -s -w '\n%{http_code}' -XPOST "$B/api/v1/links" -H "$J" -H "$K" -d '{"url":"https://spring.io/projects/spring-boot"}')
check "POST with key -> 201" 201 "$(printf '%s' "$out" | tail -n1)"
code=$(printf '%s' "$out" | head -n1 | sed -nE 's/.*"code"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p')
echo "    code = $code"
[ -n "$code" ] || { echo "  ✗ could not read the code from: $(printf '%s' "$out" | head -n1)"; exit 1; }
check "GET /$code -> 302" 302 "$(status "$B/$code")"
check "Location is the target URL" "https://spring.io/projects/spring-boot" \
      "$(curl -s -o /dev/null -w '%{redirect_url}' "$B/$code")"
check "HEAD /$code -> 302 (not counted as a click)" 302 "$(status -I "$B/$code")"
check "GET /api/v1/links/$code (public) -> 200" 200 "$(status "$B/api/v1/links/$code")"

echo "Idempotency"
idem=$(new_uuid)
check "first create with Idempotency-Key -> 201" 201 \
      "$(status -XPOST "$B/api/v1/links" -H "$J" -H "$K" -H "Idempotency-Key: $idem" -d '{"url":"https://example.org/a"}')"
check "same key, same body -> 200 (replay)" 200 \
      "$(status -XPOST "$B/api/v1/links" -H "$J" -H "$K" -H "Idempotency-Key: $idem" -d '{"url":"https://example.org/a"}')"
check "same key, different body -> 409" 409 \
      "$(status -XPOST "$B/api/v1/links" -H "$J" -H "$K" -H "Idempotency-Key: $idem" -d '{"url":"https://example.org/b"}')"

echo "Validation and errors"
check "internal URL rejected -> 400" 400 \
      "$(status -XPOST "$B/api/v1/links" -H "$J" -H "$K" -d '{"url":"http://169.254.169.254/"}')"
err=$(curl -s "$B/Unknown99")
check "unknown code -> errorCode LINK_NOT_FOUND" yes "$(printf '%s' "$err" | grep -Eq '"errorCode"[[:space:]]*:[[:space:]]*"LINK_NOT_FOUND"' && echo yes || echo no)"
check "error body carries a traceId" yes "$(printf '%s' "$err" | grep -q '"traceId"' && echo yes || echo no)"

echo "API key"
check "stats without key -> 401" 401 "$(status "$B/api/v1/links/$code/stats")"
check "stats with wrong key -> 401" 401 "$(status "$B/api/v1/links/$code/stats" -H 'X-API-Key: wrong')"
check "stats with key -> 200" 200 "$(status "$B/api/v1/links/$code/stats" -H "$K")"

echo "Delete"
check "DELETE with key -> 204" 204 "$(status -XDELETE "$B/api/v1/links/$code" -H "$K")"
check "deleted link -> 410" 410 "$(status "$B/$code")"

echo
echo "Passed: $pass   Failed: $fail"
[ "$limited" = 1 ] && echo "Some checks hit the rate limit (429): wait a minute and run again."
[ "$fail" -eq 0 ]
