package com.example.shortener.link;

import java.time.Instant;
import java.util.Objects;

/// Write model for inserting a link. `expiresAt` is optional (null = never expires); `createdBy` is the API
/// client's name (audit).
public record NewLink(String code, String targetUrl, Instant createdAt, Instant expiresAt, String createdBy) {

    public NewLink {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(targetUrl, "targetUrl");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(createdBy, "createdBy");
    }
}
