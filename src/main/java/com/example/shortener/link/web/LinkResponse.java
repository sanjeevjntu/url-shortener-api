package com.example.shortener.link.web;

import com.example.shortener.link.Link;
import com.example.shortener.link.LinkStatus;
import java.time.Instant;

/**
 * Public view of a link. {@code status} reflects lazy expiry: a link past {@code expiresAt} reads as
 * {@code EXPIRED} although the stored row is still {@code ACTIVE}.
 */
public record LinkResponse(
        String code, String shortUrl, String targetUrl, LinkStatus status, Instant createdAt, Instant expiresAt) {

    public static LinkResponse from(Link link, String baseUrl, Instant now) {
        LinkStatus status =
                link.status() == LinkStatus.ACTIVE && link.isExpiredAt(now) ? LinkStatus.EXPIRED : link.status();
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return new LinkResponse(
                link.code(), base + "/" + link.code(), link.targetUrl(), status, link.createdAt(), link.expiresAt());
    }
}
