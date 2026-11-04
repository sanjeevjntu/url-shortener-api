package com.example.shortener.analytics;

/// Port used by the redirect path. Implementations must **never block for long and never throw**: a
/// redirect must succeed even if analytics is broken (decision D4).
public interface ClickRecorder {

    void record(ClickEvent event);
}
