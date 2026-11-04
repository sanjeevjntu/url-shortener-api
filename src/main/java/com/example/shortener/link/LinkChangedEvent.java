package com.example.shortener.link;

/// Published after a link is deleted, so the redirect cache evicts that code at once. Creates publish nothing: a new
/// code is random, so a cached "not found" for it is practically impossible and would expire within `negative-ttl`.
/// Keeps the link package independent of the redirect package.
public record LinkChangedEvent(String code) {}
