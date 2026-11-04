package com.example.shortener.security;

/// The submitted URL was rejected by [UrlValidator]. The message is safe to show to the caller (it never
/// echoes the URL back).
public class InvalidUrlException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidUrlException(String message) {
        super(message);
    }
}
