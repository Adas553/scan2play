package com.scan2play.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts the application's own ERROR and WARN lines since the last {@link #take()} — for the owner's daily report
 * ({@link AdminReportService}), so a problem shows without reading Railway's log. In memory only: a restart starts from zero, and the
 * report says since when it counted. Each distinct line (the level, the logger's short name and the message cut to
 * {@value #MESSAGE_CHARS} characters) is counted on its own, at most {@value #MAX_LINES} of them; the rest only in the totals.
 * <p>
 * An appender on the root logger: it must never log itself, or a WARN about a WARN would loop.
 */
@Component
public class LogTally {

    static final int MAX_LINES = 30;
    static final int MESSAGE_CHARS = 160;

    /** One kind of line: how many times since the last take. */
    public record Line(String text, long count) {
    }

    /** What was counted between {@code since} and the take. */
    public record Tally(Instant since, long errors, long warnings, List<Line> lines) {
    }

    private final Clock clock;
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong warnings = new AtomicLong();
    private final Map<String, LongAdder> lines = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> since;
    private final Appender appender = new Appender();

    public LogTally() {
        this(Clock.systemUTC());
    }

    LogTally(Clock clock) {
        this.clock = clock;
        this.since = new AtomicReference<>(clock.instant());
    }

    /** Joins the root logger (Logback is Spring Boot's logging); with another logging system nothing is counted. */
    @PostConstruct
    void attach() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            appender.setContext(context);
            appender.setName("scan2play-log-tally");
            appender.start();
            context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        }
    }

    /** Leaves it: the tests start many contexts in one JVM, each would add one more appender. */
    @PreDestroy
    void detach() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        }
        appender.stop();
    }

    /** Counts one line, if it is a WARN or worse. */
    void count(ILoggingEvent event) {
        Level level = event.getLevel();
        if (level.isGreaterOrEqual(Level.ERROR)) {
            errors.incrementAndGet();
        } else if (level.isGreaterOrEqual(Level.WARN)) {
            warnings.incrementAndGet();
        } else {
            return;
        }
        String key = level + " " + shortName(event.getLoggerName()) + ": " + message(event);
        LongAdder adder = lines.get(key);
        if (adder == null && lines.size() < MAX_LINES) {
            adder = lines.computeIfAbsent(key, k -> new LongAdder());
        }
        if (adder != null) {
            adder.increment();
        }
    }

    /** The counts since the last take, the most frequent lines first; the counts start again from zero. */
    public Tally take() {
        Instant from = since.getAndSet(clock.instant());
        long errorCount = errors.getAndSet(0);
        long warningCount = warnings.getAndSet(0);
        List<Line> taken = lines.entrySet().stream().map(e -> new Line(e.getKey(), e.getValue().sum()))
                .sorted(Comparator.comparingLong(Line::count).reversed().thenComparing(Line::text)).toList();
        lines.clear();
        return new Tally(from, errorCount, warningCount, taken);
    }

    /** The counts since the last take, kept (a preview). */
    public Tally peek() {
        List<Line> now = lines.entrySet().stream().map(e -> new Line(e.getKey(), e.getValue().sum()))
                .sorted(Comparator.comparingLong(Line::count).reversed().thenComparing(Line::text)).toList();
        return new Tally(since.get(), errors.get(), warnings.get(), now);
    }

    private static String shortName(String logger) {
        if (logger == null) {
            return "?";
        }
        int dot = logger.lastIndexOf('.');
        return dot < 0 ? logger : logger.substring(dot + 1);
    }

    /** The message with its exception's class, on one line, cut — a stack trace or a long id would make each line its own kind. */
    private static String message(ILoggingEvent event) {
        String text = event.getFormattedMessage() == null ? "" : event.getFormattedMessage();
        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) {
            text = text + " [" + thrown.getClassName() + "]";
        }
        text = text.replaceAll("\\s+", " ").strip();
        return text.length() > MESSAGE_CHARS ? text.substring(0, MESSAGE_CHARS) + "…" : text;
    }

    private final class Appender extends AppenderBase<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            try {
                count(event);
            } catch (RuntimeException ignored) {
                // never log from here: the line would come back to this appender
            }
        }
    }
}
