package com.example.shortener.security;

import java.util.Set;

/// Permissions an API key can hold (least privilege). A dashboard key gets `stats:read` only, so a leaked
/// dashboard key can neither create nor delete links; an integration that only shortens URLs gets `links:create`.
public final class ApiScopes {

    public static final String LINKS_CREATE = "links:create";
    public static final String LINKS_DELETE = "links:delete";
    public static final String STATS_READ = "stats:read";

    /// Used to validate configuration: an unknown scope is a typo and must fail at startup.
    public static final String PATTERN = "links:create|links:delete|stats:read";

    public static final Set<String> ALL = Set.of(LINKS_CREATE, LINKS_DELETE, STATS_READ);

    private ApiScopes() {}
}
