package com.example.shortener.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. Raw 32-byte digests are stored (bytea); hex is for logs/tests only. */
public final class Fingerprints {

    public static final int SHA_256_BYTES = 32;

    private Fingerprints() {}

    public static byte[] sha256(String input) {
        try {
            // MessageDigest is not thread-safe, so create one per call (cheap; provider lookup is cached).
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform spec", e);
        }
    }

    public static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
