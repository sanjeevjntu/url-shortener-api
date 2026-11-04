package com.example.shortener;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Own Spring context with tiny budgets (decision D8). */
@TestPropertySource(
        properties = {
            "shortener.rate-limits.create.capacity=2",
            "shortener.rate-limits.create.refill-per-minute=1",
            "shortener.rate-limits.redirect.capacity=2",
            "shortener.rate-limits.redirect.refill-per-minute=1",
            "shortener.rate-limits.auth-failure.capacity=3",
            "shortener.rate-limits.auth-failure.refill-per-minute=1"
        })
class RateLimitIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void createLimit_followsTheApiKey_notTheIp() throws Exception {
        create(TestProps.API_KEY, "198.51.100.1").andExpect(status().isCreated());
        create(TestProps.API_KEY, "198.51.100.2").andExpect(status().isCreated());

        // The same key from a third address gets nothing more: a leaked key spread over many IPs stays capped.
        create(TestProps.API_KEY, "198.51.100.3")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("https://errors.shortener.example/rate-limited"));

        // Another client, same address: its own budget.
        create(TestProps.CREATOR_KEY, "198.51.100.3").andExpect(status().isCreated());
    }

    @Test
    void redirectLimit_isPerIp_andSpoofedForwardedForDoesNotHelp() throws Exception {
        // Each request claims a different client IP, but no trusted proxy is configured, so the header is ignored.
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/Unknown9").header("X-Forwarded-For", "203.0.113." + i))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/Unknown9").header("X-Forwarded-For", "203.0.113.99"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void apiKeyGuessing_isThrottledAfterAFewFailures() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(delete("/api/v1/links/Abc12345").header("X-API-Key", "guess-" + i))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(delete("/api/v1/links/Abc12345").header("X-API-Key", "guess-4"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    private ResultActions create(String apiKey, String remoteAddr) throws Exception {
        return mvc.perform(post("/api/v1/links")
                .with(request -> {
                    request.setRemoteAddr(remoteAddr);
                    return request;
                })
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/rl\"}"));
    }
}
