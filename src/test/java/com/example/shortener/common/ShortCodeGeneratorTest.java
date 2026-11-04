package com.example.shortener.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class ShortCodeGeneratorTest {

    @Test
    void producesFixedLengthCodesFromTheAlphabet() {
        ShortCodeGenerator generator = new ShortCodeGenerator(new SecureRandom(), 8);
        for (int i = 0; i < 10_000; i++) {
            String code = generator.next();
            assertThat(code).hasSize(8);
            assertThat(code.chars()).allMatch(c -> Base58.ALPHABET.indexOf(c) >= 0);
        }
    }

    @Test
    void makesExactlyOneDrawOverTheWholeSpace() {
        long[] boundSeen = {-1};
        RandomGenerator stub = new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new AssertionError("must use the bounded draw");
            }

            @Override
            public long nextLong(long bound) {
                boundSeen[0] = bound;
                return 1_234_567_890_123L;
            }
        };

        assertThat(new ShortCodeGenerator(stub, 8).next()).isEqualTo("1ZRwY92z");
        assertThat(boundSeen[0]).isEqualTo(Base58.space(8));
    }

    /**
     * Each position should be uniform over 58 characters. Chi-square with 57 degrees of freedom: the 0.1% critical
     * value is about 95. A seeded generator keeps the test deterministic.
     */
    @Test
    void everyPositionIsUniform() {
        ShortCodeGenerator generator = new ShortCodeGenerator(new SplittableRandom(42), 8);
        int samples = 290_000;
        long[][] counts = new long[8][58];
        for (int i = 0; i < samples; i++) {
            String code = generator.next();
            for (int p = 0; p < 8; p++) {
                counts[p][Base58.ALPHABET.indexOf(code.charAt(p))]++;
            }
        }
        double expected = samples / 58.0;
        for (int p = 0; p < 8; p++) {
            double chiSquare = 0;
            for (long observed : counts[p]) {
                chiSquare += (observed - expected) * (observed - expected) / expected;
            }
            assertThat(chiSquare).as("position %d", p).isLessThan(95.0);
        }
    }
}
