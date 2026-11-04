package com.example.shortener.common;

import java.util.Objects;
import java.util.random.RandomGenerator;

/// Generates random short codes: **one** uniform draw in `[0, 58^length)`, then fixed-width Base58 encoding.
///
/// - `RandomGenerator.nextLong(bound)` is unbiased (rejection sampling), and Base58 encoding is a bijection,
///   so every code is equally likely.
/// - Production passes a shared `SecureRandom` (thread-safe and unpredictable, so codes cannot be enumerated
///   from observed samples). Tests pass a seeded or stubbed generator.
/// - Uniqueness is NOT this class's job: the database's `UNIQUE(code)` decides, and the service retries.
/// - Scale-out path (decision D3): replace the random draw with a keyed permutation of a sequence value;
///   the encoder stays unchanged.
public final class ShortCodeGenerator {

    private final RandomGenerator random;
    private final int length;
    private final long space;

    public ShortCodeGenerator(RandomGenerator random, int length) {
        this.random = Objects.requireNonNull(random, "random");
        this.length = length;
        this.space = Base58.space(length); // validates length
    }

    public String next() {
        return Base58.encode(random.nextLong(space), length);
    }
}
