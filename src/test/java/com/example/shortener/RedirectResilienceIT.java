package com.example.shortener;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.analytics.ClickStatsRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * decision D4 guarantee: analytics can never fail a redirect. Uses the strictest case, {@code sync} mode (inherited
 * from the base class), where the click write happens inside the redirect request, with storage failing every time.
 */
class RedirectResilienceIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ClickStatsRepository clickStats;

    @Test
    void redirectStillSucceeds_whenAnalyticsStorageIsDown() throws Exception {
        doThrow(new DataAccessResourceFailureException("analytics storage down"))
                .when(clickStats)
                .add(anyList());
        String body = mvc.perform(post("/api/v1/links")
                        .header("X-API-Key", TestProps.API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/resilient\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String code = JsonPath.read(body, "$.code");

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/" + code))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://example.com/resilient"));
        }
        verify(clickStats, atLeastOnce()).add(anyList()); // the write really was attempted, and it really failed
    }
}
