package com.example.shortener.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator();

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "HTTPS://Example.COM:443/Path          | https://example.com/Path",
                "http://example.com                    | http://example.com/",
                "https://example.com/my%20file.pdf     | https://example.com/my%20file.pdf", // no double-encoding
                "https://example.com/a?id=42&utm_source=x#top | https://example.com/a?id=42&utm_source=x#top",
                "https://example.com/a/./b/../c        | https://example.com/a/c",
                "https://user:pw@example.com/x         | https://example.com/x",
                "http://example.com:8080/x             | http://example.com:8080/x",
                "https://bücher.de/                    | https://xn--bcher-kva.de/",
                "https://example.com/café              | https://example.com/caf%C3%A9",
                "https://example.com./                 | https://example.com/",
                "http://8.8.8.8/                       | http://8.8.8.8/",
                "http://[2001:4860:4860::8888]/        | http://[2001:4860:4860::8888]/"
            })
    void normalises(String input, String expected) {
        assertThat(validator.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(
            strings = {
                "ftp://example.com/",
                "javascript:alert(1)",
                "file:///etc/passwd",
                "http://localhost/",
                "http://foo.localhost/",
                "http://127.0.0.1/",
                "http://10.1.2.3/",
                "http://172.16.5.4/",
                "http://192.168.0.1/",
                "http://169.254.169.254/latest/meta-data",
                "http://100.64.0.1/",
                "http://0.0.0.0/",
                "http://[::1]/",
                "http://[fd00::1]/",
                "http://[fe80::1]/",
                "http://[::ffff:127.0.0.1]/",
                "http://2130706433/", // decimal form of 127.0.0.1
                "http://0x7f.1/", //     hex form
                "http://127.1/", //      short form
                "http://intranet/", //   single label: resolved via internal search domains
                "http://printer.local/",
                "http://metadata.google.internal/",
                "https://exa mple.com/",
                "https://example.com/a b",
                "http://example.com:99999/",
                "example.com",
                "//example.com/x"
            })
    void rejects(String input) {
        assertThatThrownBy(() -> validator.normalize(input)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsBlankAndOverLong() {
        assertThatThrownBy(() -> validator.normalize("  ")).isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> validator.normalize("https://example.com/" + "a".repeat(4100)))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("4096");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTPS://Example.COM:443/a/../b?x=1#f", "https://bücher.de/café?q=ü", "http://8.8.8.8:80"})
    void normalisationIsIdempotent(String input) {
        String once = validator.normalize(input);
        assertThat(validator.normalize(once)).isEqualTo(once);
    }
}
