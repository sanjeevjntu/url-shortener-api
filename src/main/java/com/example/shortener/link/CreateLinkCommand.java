package com.example.shortener.link;

import java.time.Instant;

/// Service-level input for creating a link. `expiresAt` and `idempotencyKey` are optional (null when absent).
/// `apiClient` is the authenticated client's name: stored as `created_by`, and the scope of its idempotency keys,
/// so two clients can use the same key without colliding.
public record CreateLinkCommand(String url, Instant expiresAt, String idempotencyKey, String apiClient) {}
