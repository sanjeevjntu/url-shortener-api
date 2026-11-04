package com.example.shortener.link;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NewLinkTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    @Test
    void rejectsNullRequiredFields() {
        assertThatThrownBy(() -> new NewLink(null, "https://example.com/", NOW, null, "tests"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewLink("Abc12345", null, NOW, null, "tests"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewLink("Abc12345", "https://example.com/", null, null, "tests"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewLink("Abc12345", "https://example.com/", NOW, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
