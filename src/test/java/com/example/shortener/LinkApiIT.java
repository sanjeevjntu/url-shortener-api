package com.example.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.link.LinkRepository;
import com.example.shortener.link.NewLink;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** End-to-end HTTP behaviour against real PostgreSQL: the status matrix and the uniform error format. */
class LinkApiIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    LinkRepository repository;

    @Autowired
    JdbcClient jdbc;

    // ------------------------------------------------------------------ create

    @Test
    void create_returns201_withAbsoluteLocation_andNormalisedTarget_andRecordsTheCreator() throws Exception {
        String code = codeOf(create("{\"url\":\"HTTPS://Example.COM:443/docs?id=42\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("http://localhost/api/v1/links/")))
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(jsonPath("$.code").value(matchesPattern("[1-9A-HJ-NP-Za-km-z]{8}")))
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/docs?id=42"))
                .andExpect(jsonPath("$.shortUrl").value(startsWith("http://localhost:8080/")))
                .andExpect(jsonPath("$.status").value("ACTIVE")));

        assertThat(jdbc.sql("SELECT created_by FROM links WHERE code = :c")
                        .param("c", code)
                        .query(String.class)
                        .single())
                .isEqualTo("tests");
    }

    @Test
    void create_withoutApiKey_is401_andCreatesNothing() throws Exception {
        String target = "https://example.com/anonymous-" + UUID.randomUUID();

        mvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + target + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));

        assertThat(jdbc.sql("SELECT count(*) FROM links WHERE target_url = :t")
                        .param("t", target)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void sameUrlTwice_createsTwoDifferentCodes() throws Exception { // decision D7
        String first = codeOf(create("{\"url\":\"https://example.com/same\"}"));
        String second = codeOf(create("{\"url\":\"https://example.com/same\"}"));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void invalidUrl_isUniformProblemDetail() throws Exception {
        create("{\"url\":\"http://169.254.169.254/latest/meta-data\"}")
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("https://errors.shortener.example/invalid-url"))
                .andExpect(jsonPath("$.errorCode").value("INVALID_URL"))
                .andExpect(jsonPath("$.detail").value("URL host is not allowed"))
                .andExpect(jsonPath("$.instance").value("/api/v1/links"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void bodyValidation_listsFieldErrors() throws Exception {
        create("{\"url\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("url"));
        create("{\"url\":\"https://example.com/\",\"expiresAt\":\"2000-01-01T00:00:00Z\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        create("not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void idempotencyKey_replaysSameRequest_andRejectsDifferentBody() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"url\":\"https://example.com/idem\"}";

        String code = codeOf(createIdempotent(body, key).andExpect(status().isCreated()));

        createIdempotent(body, key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.code").value(code));

        createIdempotent("{\"url\":\"https://example.com/other\"}", key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void idempotencyKey_isScopedPerApiClient_notShared() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"url\":\"https://example.com/two-clients\"}";

        String ours = codeOf(createIdempotent(body, key).andExpect(status().isCreated()));
        String theirs = codeOf(mvc.perform(post("/api/v1/links")
                        .header("X-API-Key", TestProps.CREATOR_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())); // not a replay of our link

        assertThat(theirs).isNotEqualTo(ours);
    }

    @Test
    void idempotencyKey_replayAfterDelete_is410_notADeadLinkWith200() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"url\":\"https://example.com/replay-after-delete\"}";
        String code = codeOf(createIdempotent(body, key).andExpect(status().isCreated()));
        mvc.perform(delete("/api/v1/links/" + code).header("X-API-Key", TestProps.API_KEY))
                .andExpect(status().isNoContent());

        createIdempotent(body, key)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("LINK_GONE"));
        createIdempotent(body, UUID.randomUUID().toString()).andExpect(status().isCreated()); // a new key works
    }

    // ------------------------------------------------------------------ read and redirect

    @Test
    void redirect_is302ToTarget_notCachedByBrowsers() throws Exception {
        String code = codeOf(create("{\"url\":\"https://example.com/landing?utm_source=x\"}"));

        mvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/landing?utm_source=x"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void unknownCode_is404_andMalformedCode_is400() throws Exception {
        mvc.perform(get("/Unknown9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LINK_NOT_FOUND"));
        mvc.perform(get("/api/v1/links/bad!code")).andExpect(status().isBadRequest());
    }

    @Test
    void expiredLink_is410_andReportedAsExpired() throws Exception {
        String code = "Exp" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repository.tryInsert(new NewLink(
                code, "https://example.com/old", now.minus(2, ChronoUnit.HOURS), now.minusSeconds(60), "tests"));

        mvc.perform(get("/" + code))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("LINK_GONE"));
        mvc.perform(get("/api/v1/links/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    // ------------------------------------------------------------------ delete (API key)

    @Test
    void delete_requiresApiKey_recordsWhoDeleted_thenLinkIsGone() throws Exception {
        String code = codeOf(create("{\"url\":\"https://example.com/to-delete\"}"));
        mvc.perform(get("/" + code)).andExpect(status().isFound()); // now cached

        mvc.perform(delete("/api/v1/links/" + code))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        mvc.perform(delete("/api/v1/links/" + code).header("X-API-Key", "wrong"))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/v1/links/" + code).header("X-API-Key", TestProps.API_KEY))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT deleted_by FROM links WHERE code = :c")
                        .param("c", code)
                        .query(String.class)
                        .single())
                .isEqualTo("tests");
        mvc.perform(get("/" + code)).andExpect(status().isGone()); // cache was evicted
        mvc.perform(get("/api/v1/links/" + code)).andExpect(status().isGone());

        mvc.perform(delete("/api/v1/links/" + code).header("X-API-Key", TestProps.API_KEY))
                .andExpect(status().isNoContent()); // idempotent
        mvc.perform(delete("/api/v1/links/Unknown9").header("X-API-Key", TestProps.API_KEY))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ analytics

    @Test
    void stats_countClicksByDayReferrerAndBrowser() throws Exception {
        String code = codeOf(create("{\"url\":\"https://example.com/stats\"}"));
        String chrome = "Mozilla/5.0 (X11; Linux) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/" + code)
                    .header("Referer", "https://news.example.org/post")
                    .header("User-Agent", chrome));
        }
        mvc.perform(get("/" + code).header("User-Agent", "curl/8.5.0"));
        mvc.perform(head("/" + code)).andExpect(status().isFound()); // link checkers: answered, not counted

        mvc.perform(get("/api/v1/links/" + code + "/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/links/" + code + "/stats").header("X-API-Key", TestProps.API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(4))
                .andExpect(jsonPath("$.topReferrers[0].name").value("news.example.org"))
                .andExpect(jsonPath("$.topReferrers[0].clicks").value(3))
                .andExpect(jsonPath("$.browsers[0].name").value("Chrome"))
                .andExpect(jsonPath("$.daily").isNotEmpty()); // not "== 1": the test may run across midnight UTC
        mvc.perform(get("/api/v1/links/" + code + "/stats?days=0").header("X-API-Key", TestProps.API_KEY))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ contract

    @Test
    void openApiSpecIsPublished() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/links']").exists());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions create(String json) throws Exception {
        return mvc.perform(post("/api/v1/links")
                .header("X-API-Key", TestProps.API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions createIdempotent(String json, String idempotencyKey) throws Exception {
        return mvc.perform(post("/api/v1/links")
                .header("X-API-Key", TestProps.API_KEY)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static String codeOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.code");
    }
}
