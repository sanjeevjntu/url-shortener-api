package com.example.shortener;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Named, scoped, expiring API keys (decision D6): 401 = who are you?, 403 = you may not do this. */
class ApiKeySecurityIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void readOnlyKey_canReadStats_butCannotCreateOrDelete() throws Exception {
        String code = newLink(TestProps.API_KEY);

        mvc.perform(get("/api/v1/links/" + code + "/stats").header("X-API-Key", TestProps.READONLY_KEY))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/links/" + code).header("X-API-Key", TestProps.READONLY_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("API key 'readonly' lacks scope 'links:delete'"));
        create(TestProps.READONLY_KEY)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("API key 'readonly' lacks scope 'links:create'"));
    }

    @Test
    void createOnlyKey_canCreate_butCannotDeleteOrReadStats() throws Exception {
        String code = newLink(TestProps.CREATOR_KEY);

        mvc.perform(delete("/api/v1/links/" + code).header("X-API-Key", TestProps.CREATOR_KEY))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/links/" + code + "/stats").header("X-API-Key", TestProps.CREATOR_KEY))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicEndpoints_needNoKey() throws Exception {
        String code = newLink(TestProps.API_KEY);

        mvc.perform(get("/" + code)).andExpect(status().isFound());
        mvc.perform(get("/api/v1/links/" + code)).andExpect(status().isOk());
    }

    @Test
    void expiredKey_isRejected_evenThoughItMatches() throws Exception {
        mvc.perform(delete("/api/v1/links/" + newLink(TestProps.API_KEY)).header("X-API-Key", TestProps.EXPIRED_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("API key 'expired' has expired"));
    }

    @Test
    void missingOrUnknownKey_is401() throws Exception {
        String code = newLink(TestProps.API_KEY);
        mvc.perform(delete("/api/v1/links/" + code))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        mvc.perform(delete("/api/v1/links/" + code).header("X-API-Key", "not-a-real-key"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/links").header("X-API-Key", "not-a-real-key")).andExpect(status().isUnauthorized());
    }

    private ResultActions create(String apiKey) throws Exception {
        return mvc.perform(post("/api/v1/links")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/keys\"}"));
    }

    private String newLink(String apiKey) throws Exception {
        String body = create(apiKey)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.code");
    }
}
