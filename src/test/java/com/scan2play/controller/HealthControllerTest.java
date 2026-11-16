package com.scan2play.controller;

import com.scan2play.config.NoIndexFilter;
import com.scan2play.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCallback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /healthz}: what the uptime monitor asks — public, no session, a 503 when the database does not answer. */
@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, NoIndexFilter.class, com.scan2play.service.PlansOffConfig.class})
class HealthControllerTest {

    @MockitoBean
    private JdbcTemplate jdbc;
    @MockitoBean
    private com.scan2play.service.SiteCounter siteCounter;

    @Autowired
    private MockMvc mockMvc;

    private Instant now = Instant.parse("2026-11-16T20:00:00Z");

    @Test
    void withTheDatabase_itIsOk_withoutALogin_aSession_orASearchEngine() throws Exception {
        mockMvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).as("no session is made").isNull());
    }

    /**
     * Anyone may ask: a flood of the address asks the database once in {@link HealthController#FRESH}; a database that does not
     * answer is a 503.
     */
    @Test
    void theDatabase_isAskedOnceInAWhile_howeverOftenTheAddressIs() {
        JdbcTemplate database = mock(JdbcTemplate.class);
        HealthController controller = new HealthController(database, new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        });

        for (int i = 0; i < 50; i++) {
            ResponseEntity<String> answer = controller.health();
            assertThat(answer.getBody()).isEqualTo("ok");
        }
        verify(database, times(1)).execute(any(StatementCallback.class));

        now = now.plus(HealthController.FRESH).plus(Duration.ofMillis(1));
        when(database.execute(any(StatementCallback.class))).thenThrow(new DataAccessResourceFailureException("gone"));
        ResponseEntity<String> away = controller.health();
        assertThat(away.getStatusCode().value()).as("asked again, and the database is away").isEqualTo(503);
        assertThat(away.getBody()).isEqualTo("database");
        verify(database, times(2)).execute(any(StatementCallback.class));
    }
}
