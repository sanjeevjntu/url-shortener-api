package com.example.shortener.security;

/// Separate buckets per purpose: strict for creating links, lenient for following them, and a small one that
/// only failed API-key attempts consume (brute-force protection).
public enum RateLimitPolicy {
    CREATE,
    REDIRECT,
    AUTH_FAILURE
}
