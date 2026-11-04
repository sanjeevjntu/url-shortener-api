package com.example.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortener.TestProps;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** The same Bean Validation rules Spring applies at startup: invalid config means the app does not start. */
class ShortenerPropertiesTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @Test
    void defaultsAreValid() {
        assertThat(VALIDATOR.validate(TestProps.defaults())).isEmpty();
    }

    @Test
    void keyMustBeAHash_notAPlaintextKey() {
        var plaintext = List.of(new ShortenerProperties.ApiKey("ops", "my-secret-key", List.of("stats:read"), null));

        assertThat(VALIDATOR.validate(withKeys(plaintext))).isNotEmpty();
    }

    @Test
    void unknownScope_isRejected() {
        var typo = List.of(
                new ShortenerProperties.ApiKey("ops", TestProps.API_KEY_SHA256, List.of("links:destroy"), null));

        assertThat(VALIDATOR.validate(withKeys(typo))).isNotEmpty();
    }

    @Test
    void keyNeedsAtLeastOneScope() {
        var none = List.of(new ShortenerProperties.ApiKey("ops", TestProps.API_KEY_SHA256, List.of(), null));

        assertThat(VALIDATOR.validate(withKeys(none))).isNotEmpty();
    }

    private static ShortenerProperties withKeys(List<ShortenerProperties.ApiKey> keys) {
        ShortenerProperties d = TestProps.defaults();
        return new ShortenerProperties(
                d.baseUrl(), keys, d.code(), d.cache(), d.analytics(), d.rateLimits(), d.idempotencyTtl());
    }
}
