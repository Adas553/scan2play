package com.scan2play.service;

import com.scan2play.service.GuestRequestLimiter.PartyDay;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the guests' limits that cost money or stop a party across a restart (V71): each party's requests of its 24 hours
 * ({@link GuestRequestLimiter}) and the Free plan's AI checks of an evening ({@link FreeAiLimit}). Both count in memory — a
 * request never waits for the database to be counted —, and this writes what they hold once a minute, and once more when the
 * application stops; the start reads it back. A deploy during a party therefore goes on from the counts of a minute before at
 * the worst, instead of from zero.
 * <p>
 * Only what changed since the last write is written, in one transaction: the rows whose count moved, and the rows of the
 * windows this instance no longer holds are deleted (ended, or started again by "Odblokuj prośby gości") — a few rows a minute
 * at a busy hour. A single instance by design (Section 13 of {@code PROJECT_CONTEXT.md}): during a deploy the old and the new
 * instance both write for a moment, and the counts the old one made after the new one started are lost — in the DJ's favour. A
 * failure here is a line in the log, never an error of a request or of the start.
 */
@Service
@Slf4j
public class GuestLimitStore {

    static final String PARTY_DAY = "PARTY_DAY";
    static final String FREE_AI = "FREE_AI";

    /** A row's primary key. */
    private record Id(String kind, String key) {
    }

    /** What a row holds: when its window started (a party's day; unused for an evening) and its count. */
    private record Counted(Instant startedAt, int count) {
    }

    private final GuestRequestLimiter limiter;
    private final FreeAiLimit freeAiLimit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    /** The rows of the table as this instance last read or wrote them. */
    private Map<Id, Counted> stored = Map.of();

    public GuestLimitStore(GuestRequestLimiter limiter, FreeAiLimit freeAiLimit, JdbcTemplate jdbc,
                           PlatformTransactionManager transactionManager) {
        this.limiter = limiter;
        this.freeAiLimit = freeAiLimit;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * At the start, once Flyway has made the table: the counts of before the restart, the windows still open. The rows of the
     * windows that have ended are remembered too — the first write deletes them.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void atStart() {
        try {
            Map<Id, Counted> read = new HashMap<>();
            // bounded: the table is what one instance held in memory (at most the limits' own bounds)
            jdbc.query("SELECT kind, key, started_at, count FROM guest_limit_count", rows -> {
                read.put(new Id(rows.getString("kind"), rows.getString("key")),
                        new Counted(rows.getTimestamp("started_at").toInstant(), rows.getInt("count")));
            });
            Map<String, PartyDay> partyDays = new HashMap<>();
            Map<String, Integer> evenings = new HashMap<>();
            read.forEach((id, counted) -> {
                if (PARTY_DAY.equals(id.kind())) {
                    partyDays.put(id.key(), new PartyDay(counted.startedAt(), counted.count()));
                } else if (FREE_AI.equals(id.kind())) {
                    evenings.put(id.key(), counted.count());
                }
            });
            limiter.restorePartyDays(partyDays);
            freeAiLimit.restore(evenings);
            stored = read;
            log.info("Guest limits read back: {} parties' days, {} evenings of the Free plan's AI checks",
                    partyDays.size(), evenings.size());
        } catch (RuntimeException e) {
            log.warn("Guest limits not read back — they start from zero: {}", e.getMessage());
        }
    }

    /** Once a minute, and at the stop (after the requests in flight have ended): what changed since the last write. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @PreDestroy
    public synchronized void save() {
        try {
            Map<Id, Counted> now = held();
            List<Id> gone = stored.keySet().stream().filter(id -> !now.containsKey(id)).toList();
            List<Map.Entry<Id, Counted>> changed = now.entrySet().stream()
                    .filter(row -> !row.getValue().equals(stored.get(row.getKey()))).toList();
            if (gone.isEmpty() && changed.isEmpty()) {
                return;
            }
            transaction.executeWithoutResult(status -> {
                jdbc.batchUpdate("DELETE FROM guest_limit_count WHERE kind = ? AND key = ?", gone, 500, (statement, id) -> {
                    statement.setString(1, id.kind());
                    statement.setString(2, id.key());
                });
                jdbc.batchUpdate("INSERT INTO guest_limit_count (kind, key, started_at, count) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (kind, key) DO UPDATE SET started_at = EXCLUDED.started_at, count = EXCLUDED.count",
                        changed, 500, (statement, row) -> {
                            statement.setString(1, row.getKey().kind());
                            statement.setString(2, row.getKey().key());
                            statement.setTimestamp(3, Timestamp.from(row.getValue().startedAt()));
                            statement.setInt(4, row.getValue().count());
                        });
            });
            stored = now;
        } catch (RuntimeException e) {
            log.warn("Guest limits not written — tried again in a minute: {}", e.getMessage());
        }
    }

    /** What both limits hold now. */
    private Map<Id, Counted> held() {
        Map<Id, Counted> held = new HashMap<>();
        limiter.partyDays().forEach((party, day) -> held.put(new Id(PARTY_DAY, party), new Counted(day.start(), day.count())));
        freeAiLimit.counted().forEach((key, count) -> held.put(new Id(FREE_AI, key), new Counted(Instant.EPOCH, count)));
        return held;
    }
}
