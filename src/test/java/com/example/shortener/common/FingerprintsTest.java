package com.example.shortener.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FingerprintsTest {

    @Test
    void sha256_isStable_and32Bytes() {
        byte[] a = Fingerprints.sha256("https://example.com/");
        byte[] b = Fingerprints.sha256("https://example.com/");

        assertThat(a).hasSize(Fingerprints.SHA_256_BYTES).isEqualTo(b);
    }

    @Test
    void sha256_matchesKnownVector() {
        // NIST/RFC test vector: SHA-256("abc")
        assertThat(Fingerprints.hex(Fingerprints.sha256("abc")))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sha256_differentInputs_differentDigests() {
        assertThat(Fingerprints.sha256("https://example.com/a"))
                .isNotEqualTo(Fingerprints.sha256("https://example.com/b"));
    }
}
