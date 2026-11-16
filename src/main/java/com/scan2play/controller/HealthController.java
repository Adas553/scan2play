package com.scan2play.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * What an uptime monitor outside asks ({@code docs/monitoring.md}): {@code GET /healthz} is "ok" while the application answers
 * <b>and</b> reaches its database, else a 503 — a page that needs no database (the landing page, {@code /privacy}) stays a 200
 * while every party is down. Railway's own deploy healthcheck stays {@code /privacy}: a database away for a moment must not
 * fail a deploy that is otherwise fine.
 * <p>
 * Public, without a session. The database is asked at most once in {@link #FRESH}: the address is open to anyone, and a flood
 * of it must not take the pool's connections from the parties.
 */
@RestController
@Slf4j
public class HealthController {

    /** How long one answer of the database is given again. */
    static final Duration FRESH = Duration.ofSeconds(5);

    /** The longest the check waits for the database (seconds), once it has a connection; a monitor's own timeout is longer. */
    private static final int QUERY_TIMEOUT_SECONDS = 2;

    private record Checked(Instant at, boolean ok) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Checked last;

    @Autowired
    public HealthController(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    /** With a clock of the test's own. */
    HealthController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping(value = "/healthz", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> health() {
        boolean ok = databaseAnswers();
        return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(ok ? "ok" : "database");
    }

    private boolean databaseAnswers() {
        Instant now = clock.instant();
        Checked checked = last;
        if (checked != null && now.isBefore(checked.at().plus(FRESH))) {
            return checked.ok();
        }
        boolean ok;
        try {
            jdbc.execute((StatementCallback<Boolean>) statement -> {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                return statement.execute("SELECT 1");
            });
            ok = true;
        } catch (RuntimeException e) {
            // WARN, not ERROR: the owner's daily report counts errors, and the pages that failed have written theirs
            log.warn("Health check: the database did not answer — {}", e.getMessage());
            ok = false;
        }
        last = new Checked(now, ok);
        return ok;
    }
}
