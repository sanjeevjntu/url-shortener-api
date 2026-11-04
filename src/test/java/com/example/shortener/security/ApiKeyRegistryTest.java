package com.example.shortener.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortener.TestProps;
import com.example.shortener.config.ShortenerProperties;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ApiKeyRegistryTest {

    private final Clock clock = Clock.systemUTC();

    @Test
    void noKey_outsideLocalProfile_refusesToStart() {
        assertThatThrownBy(() -> new ApiKeyRegistry(withoutKeys(), new MockEnvironment(), clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No API key configured");
    }

    @Test
    void noKey_inLocalProfile_generatesOneForTheRun() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles(ApiKeyRegistry.LOCAL_PROFILE);

        ApiKeyRegistry registry = new ApiKeyRegistry(withoutKeys(), local, clock);

        assertThat(registry.clientNames()).containsExactly("dev");
        assertThat(registry.match("some-guess")).isEmpty();
    }

    @Test
    void noKey_withLocalAsDefaultProfile_generatesOne() { // started from a checkout: config/application.yml
        MockEnvironment checkout = new MockEnvironment();
        checkout.setDefaultProfiles(ApiKeyRegistry.LOCAL_PROFILE);

        assertThat(new ApiKeyRegistry(withoutKeys(), checkout, clock).clientNames())
                .containsExactly("dev");
    }

    @Test
    void noKey_withAnExplicitProfile_refusesToStart_evenIfLocalIsTheDefault() {
        MockEnvironment prod = new MockEnvironment();
        prod.setDefaultProfiles(ApiKeyRegistry.LOCAL_PROFILE);
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> new ApiKeyRegistry(withoutKeys(), prod, clock))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void configuredKeys_areUsed_andNothingIsGenerated() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles(ApiKeyRegistry.LOCAL_PROFILE);

        ApiKeyRegistry registry = new ApiKeyRegistry(TestProps.defaults(), local, clock);

        assertThat(registry.clientNames()).containsExactly("tests");
        assertThat(registry.match(TestProps.API_KEY))
                .hasValueSatisfying(c -> assertThat(c.scopes()).containsExactlyInAnyOrderElementsOf(ApiScopes.ALL));
        assertThat(registry.match("wrong")).isEmpty();
    }

    private static ShortenerProperties withoutKeys() {
        ShortenerProperties d = TestProps.defaults();
        return new ShortenerProperties(
                d.baseUrl(), List.of(), d.code(), d.cache(), d.analytics(), d.rateLimits(), d.idempotencyTtl());
    }
}
