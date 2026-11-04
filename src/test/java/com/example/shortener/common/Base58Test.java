package com.example.shortener.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Base58Test {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "1234567890123, 1ZRwY92z", // worked example from decision D3
        "0, 11111111", //              zero is all '1' (the zero digit)
        "57, 1111111z",
        "58, 11111121", //             first carry
        "128063081718015, zzzzzzzz" // 58^8 - 1, the largest 8-digit value
    })
    void encodesKnownVectors(long value, String code) {
        assertThat(Base58.encode(value, 8)).isEqualTo(code);
        assertThat(Base58.decode(code)).isEqualTo(value);
    }

    @Test
    void isABijectionAtWidth3_exhaustively() {
        long space = Base58.space(3);
        Set<String> codes = new HashSet<>();
        for (long v = 0; v < space; v++) {
            String code = Base58.encode(v, 3);
            assertThat(Base58.decode(code)).isEqualTo(v);
            codes.add(code);
        }
        assertThat(codes).hasSize(195_112);
    }

    @Test
    void alphabetHas58DistinctCharsWithoutLookAlikes() {
        assertThat(Base58.ALPHABET.chars().distinct().count()).isEqualTo(58);
        assertThat(Base58.ALPHABET).doesNotContain("0", "O", "I", "l");
    }

    @Test
    void rejectsValuesOutsideTheSpace() {
        assertThatThrownBy(() -> Base58.encode(Base58.space(8), 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base58.encode(-1, 8)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void widthIsBoundedSoTheSpaceFitsInALong() {
        assertThat(Base58.space(10)).isEqualTo(430_804_206_899_405_824L);
        assertThatThrownBy(() -> Base58.space(11)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base58.space(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodeRejectsCharactersOutsideTheAlphabet() {
        assertThatThrownBy(() -> Base58.decode("abc0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base58.decode("abcé")).isInstanceOf(IllegalArgumentException.class);
    }
}
