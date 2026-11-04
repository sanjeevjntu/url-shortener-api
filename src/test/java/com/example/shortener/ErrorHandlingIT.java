package com.example.shortener;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shortener.redirect.RedirectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Every failure, including bugs and Spring's own errors, leaves in the same problem-detail shape. */
class ErrorHandlingIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RedirectService redirects;

    @Test
    void unexpectedBug_is500_withoutLeakingInternals_andCarriesTheTraceId() throws Exception {
        when(redirects.resolve(anyString())).thenThrow(new IllegalStateException("secret internal detail"));

        mvc.perform(get("/Abc12345").header("X-Trace-Id", "trace-from-client-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").value("trace-from-client-1"))
                .andExpect(header().string("X-Trace-Id", "trace-from-client-1"))
                .andExpect(content().string(not(containsString("secret internal detail"))));
    }

    @Test
    void unknownRoute_andWrongMethod_useTheSameFormat() throws Exception {
        mvc.perform(get("/api/v1/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").exists());
        mvc.perform(put("/api/v1/links"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
    }
}
