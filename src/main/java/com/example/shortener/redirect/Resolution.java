package com.example.shortener.redirect;

/// Outcome of resolving a short code. Sealed, so the controller's `switch` must handle every case and the
/// compiler proves it (no `default` branch that could hide a forgotten case).
public sealed interface Resolution {

    /// Live link: answer `302` to `targetUrl`.
    record Redirect(String code, String targetUrl) implements Resolution {}

    /// Existed once but is expired or deleted: answer `410 Gone`.
    record Gone(String code) implements Resolution {}

    /// Never existed: answer `404`.
    record NotFound(String code) implements Resolution {}
}
