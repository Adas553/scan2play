package com.scan2play.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** The log's ERROR and WARN lines counted for the owner's report: by kind, at most {@value LogTally#MAX_LINES} kinds, from zero after a take. */
class LogTallyTest {

    private static LoggingEvent event(Level level, String logger, String message, Throwable thrown) {
        LoggerContext context = new LoggerContext();
        return new LoggingEvent(LogTallyTest.class.getName(), context.getLogger(logger), level, message, thrown, null);
    }

    @Test
    void errorsAndWarnings_areCountedByKind_theRestNot() {
        LogTally tally = new LogTally(Clock.fixed(Instant.parse("2026-11-08T08:10:00Z"), ZoneOffset.UTC));
        // the numbers in a message do not make a kind of their own
        tally.count(event(Level.ERROR, "com.scan2play.service.AiHealthMonitor", "AI check: 3 of 5", null));
        tally.count(event(Level.ERROR, "com.scan2play.service.AiHealthMonitor", "AI check: 12 of 40", null));
        tally.count(event(Level.WARN, "com.scan2play.service.Mailer", "E-mail not sent:\n  timeout", new IllegalStateException("x")));
        tally.count(event(Level.INFO, "com.scan2play.Foo", "Started", null));

        LogTally.Tally peeked = tally.peek();
        LogTally.Tally taken = tally.take();

        assertThat(peeked).isEqualTo(taken);
        assertThat(taken.since()).isEqualTo(Instant.parse("2026-11-08T08:10:00Z"));
        assertThat(taken.errors()).isEqualTo(2);
        assertThat(taken.warnings()).isEqualTo(1);
        assertThat(taken.lines()).containsExactly(new LogTally.Line("ERROR AiHealthMonitor: AI check: # of #", 2),
                new LogTally.Line("WARN Mailer: E-mail not sent: timeout [java.lang.IllegalStateException]", 1));
        LogTally.Tally next = tally.take();
        assertThat(next.errors()).isZero();
        assertThat(next.lines()).isEmpty();
    }

    @Test
    void manyKinds_onlyTheFirstAreKept_everyLineCounted_andALongOneIsCut() {
        LogTally tally = new LogTally();
        for (int i = 0; i < LogTally.MAX_LINES + 5; i++) {
            tally.count(event(Level.WARN, "x.Y", Character.toString('a' + i) + " party " + "z".repeat(300), null));
        }

        LogTally.Tally taken = tally.take();

        assertThat(taken.warnings()).isEqualTo(LogTally.MAX_LINES + 5);
        assertThat(taken.lines()).hasSize(LogTally.MAX_LINES);
        assertThat(taken.lines().getFirst().text()).endsWith("…").hasSizeLessThan(LogTally.MESSAGE_CHARS + 20);
    }

    @Test
    void attached_itCountsTheApplicationsOwnLog() {
        LogTally tally = new LogTally();
        tally.attach();
        try {
            LoggerFactory.getLogger("com.scan2play.LogTallyProbe").warn("a probe {}", 42);
        } finally {
            tally.detach();
        }
        LoggerFactory.getLogger("com.scan2play.LogTallyProbe").warn("after the detach");

        // the message's pattern, not its arguments: one kind for every party
        assertThat(tally.take().lines()).containsExactly(new LogTally.Line("WARN LogTallyProbe: a probe {}", 1));
    }

    @Test
    void aTallyGivenBack_isInTheNextTake_fromItsOwnStart() {
        Instant nine = Instant.parse("2026-11-08T08:10:00Z");
        LogTally tally = new LogTally(Clock.fixed(nine, ZoneOffset.UTC));
        tally.count(event(Level.ERROR, "x.Y", "boom", null));
        LogTally.Tally taken = tally.take();
        tally.count(event(Level.ERROR, "x.Y", "boom", null));
        tally.count(event(Level.WARN, "x.Y", "careful", null));

        tally.giveBack(taken);

        LogTally.Tally next = tally.take();
        assertThat(next.since()).isEqualTo(nine);
        assertThat(next.errors()).isEqualTo(2);
        assertThat(next.warnings()).isEqualTo(1);
        assertThat(next.lines()).containsExactly(new LogTally.Line("ERROR Y: boom", 2), new LogTally.Line("WARN Y: careful", 1));
    }
}
