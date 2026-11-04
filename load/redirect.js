// k6 load smoke for the redirect hot path (tasks T9, T11).
// Usage: see load/README.md. Example:
//   k6 run -e API_KEY=<key from the app log> -e MODE=async load/redirect.js
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MODE = __ENV.MODE || 'unknown'; // label only: the server's analytics mode is set when starting the app
const API_KEY = __ENV.API_KEY; // creating the test links needs links:create; redirects themselves are public
if (!API_KEY) {
  throw new Error('Pass the API key from the app log: k6 run -e API_KEY=<key> ...');
}

export const options = {
  scenarios: {
    redirects: {
      executor: 'constant-arrival-rate', // fixed request rate, so latency is comparable between runs
      rate: Number(__ENV.RATE || 500),   // requests per second
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 50,
      maxVUs: 400,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],                  // < 1% errors
    'http_req_duration{kind:redirect}': ['p(99)<50'], // smoke target chosen for this test, not a stated SLO
  },
  tags: { mode: MODE },
};

// Creates a handful of links once; every virtual user then redirects through them.
export function setup() {
  const codes = [];
  for (let i = 0; i < 5; i++) {
    const res = http.post(
      `${BASE_URL}/api/v1/links`,
      JSON.stringify({ url: `https://example.com/load/${i}` }),
      { headers: { 'Content-Type': 'application/json', 'X-API-Key': API_KEY } },
    );
    check(res, { 'link created': (r) => r.status === 201 });
    codes.push(res.json('code'));
  }
  return { codes };
}

export default function (data) {
  const code = data.codes[Math.floor(Math.random() * data.codes.length)];
  const res = http.get(`${BASE_URL}/${code}`, {
    redirects: 0, // measure our 302, not the target site
    headers: { Referer: 'https://news.example.org/post', 'User-Agent': 'k6-load-test' },
    tags: { kind: 'redirect' },
  });
  check(res, { '302': (r) => r.status === 302 });
}
