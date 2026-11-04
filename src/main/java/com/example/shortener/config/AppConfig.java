package com.example.shortener.config;

import com.example.shortener.common.ShortCodeGenerator;
import com.example.shortener.security.UrlValidator;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Wires the framework-free building blocks. Keeping them plain classes makes them trivially unit-testable.
@Configuration(proxyBeanMethods = false)
public class AppConfig {

    /// Injected everywhere "now" matters, so expiry and analytics tests can use a fixed clock.
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /// One shared `SecureRandom`: thread-safe, non-blocking (never `getInstanceStrong()` on a server).
    @Bean
    ShortCodeGenerator shortCodeGenerator(ShortenerProperties props) {
        return new ShortCodeGenerator(new SecureRandom(), props.code().length());
    }

    @Bean
    UrlValidator urlValidator() {
        return new UrlValidator();
    }
}
