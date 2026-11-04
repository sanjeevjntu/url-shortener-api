package com.example.shortener.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Marks a management endpoint that needs an `X-API-Key` holding the given scope (decision D6).
/// Enforced by [ApiKeyInterceptor]: no or bad key is `401`, a valid key without the scope is `403`.
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresApiKey {

    /// One of [ApiScopes], e.g. `ApiScopes.LINKS_DELETE`.
    String scope();
}
