package com.example.shortener.common;

import java.util.Arrays;

/// Fixed-width Base58 codec (decision D3, "Option B").
///
/// A number in `[0, 58^width)` is written as exactly `width` base-58 digits, most significant first,
/// left-padded with the zero digit `1`. Because every number in range maps to exactly one string (and back),
/// a **uniform** random number produces a **uniform** code.
///
/// The alphabet is Bitcoin's: digits and letters without the look-alikes `0 O I l`.
/// This class is pure (no randomness, no I/O), so it is tested against fixed vectors.
public final class Base58 {

    public static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    public static final int RADIX = 58;

    /// 58^10 is about 4.3e17 and fits in a long; 58^11 overflows.
    public static final int MAX_WIDTH = 10;

    private static final int[] INDEX = new int[128];

    static {
        Arrays.fill(INDEX, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            INDEX[ALPHABET.charAt(i)] = i;
        }
    }

    private Base58() {}

    /// Number of distinct codes of the given width: `58^width`.
    public static long space(int width) {
        checkWidth(width);
        long space = 1;
        for (int i = 0; i < width; i++) {
            space *= RADIX;
        }
        return space;
    }

    /// Encodes `value` as exactly `width` characters. Example: `encode(1_234_567_890_123L, 8)` is `"1ZRwY92z"`.
    public static String encode(long value, int width) {
        if (value < 0 || value >= space(width)) {
            throw new IllegalArgumentException("value " + value + " does not fit in " + width + " Base58 digits");
        }
        char[] out = new char[width];
        long remaining = value;
        // Always do `width` divisions: once the number reaches 0 the remaining digits are '1' (padding).
        for (int i = width - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (remaining % RADIX));
            remaining /= RADIX;
        }
        return new String(out);
    }

    /// Inverse of [#encode(long, int)]. Used by tests to prove the mapping is lossless.
    public static long decode(String code) {
        checkWidth(code.length());
        long value = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            int digit = c < INDEX.length ? INDEX[c] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("not a Base58 character: '" + c + "'");
            }
            value = value * RADIX + digit;
        }
        return value;
    }

    private static void checkWidth(int width) {
        if (width < 1 || width > MAX_WIDTH) {
            throw new IllegalArgumentException("width must be 1.." + MAX_WIDTH + ", was " + width);
        }
    }
}
