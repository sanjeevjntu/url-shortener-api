package com.example.shortener.link.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Body of {@code POST /api/v1/links}. Bean validation does the cheap shape checks; the service's
 * {@code UrlValidator} does the real URL checks.
 */
public record CreateLinkRequest(
        @Schema(example = "https://spring.io/projects/spring-boot?utm_source=newsletter") @NotBlank @Size(max = 4096)
                String url,
        @Schema(
                        description = "Optional absolute UTC expiry; afterwards the link answers 410 Gone",
                        example = "2027-01-01T00:00:00Z")
                Instant expiresAt) {}
