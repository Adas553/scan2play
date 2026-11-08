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
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/**
 * Counts the application's own ERROR and WARN lines since the last {@link #take()} — for the owner's daily report
 * ({@link AdminReportService}), so a problem shows without reading Railway's log. In memory only: a restart starts from zero, and the
 * report says since when it counted. Each kind of line (the level, the logger's short name and the message's pattern, see
 * {@link #message}) is counted on its own, at most {@value #MAX_LINES} kinds; the rest only in the totals.
 * <p>
 * An appender on the root logger: it must never log itself, or a WARN about a WARN would loop.
 */
@Component
public class LogTally {

    static final int MAX_LINES = 30;
    static final int MESSAGE_CHARS = 160;
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** One kind of line: how many times since the last take. */
    public record Line(String text, long count) {
    }

    /** What was counted between {@code since} and the take. */
    public record Tally(Instant since, long errors, long warnings, List<Line> lines) {
    }

    private final Clock clock;
    /** A take swaps the period's counts at once: a line counted meanwhile waits for the lock and lands in the next period. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Appender appender = new Appender();
    private Instant since;
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong warnings = new AtomicLong();
    private final Map<String, LongAdder> lines = new ConcurrentHashMap<>();

    public LogTally() {
        this(Clock.systemUTC());
    }

    LogTally(Clock clock) {
        this.clock = clock;
        this.since = clock.instant();
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
        boolean error = level.isGreaterOrEqual(Level.ERROR);
        if (!error && !level.isGreaterOrEqual(Level.WARN)) {
            return;
        }
        String key = level + " " + shortName(event.getLoggerName()) + ": " + message(event);
        lock.readLock().lock();
        try {
            (error ? errors : warnings).incrementAndGet();
            add(key, 1);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Adds to a kind's count; a new kind past {@value #MAX_LINES} is only in the totals. */
    private void add(String key, long count) {
        LongAdder adder = lines.get(key);
        if (adder == null && lines.size() < MAX_LINES) {
            adder = lines.computeIfAbsent(key, k -> new LongAdder());
        }
        if (adder != null) {
            adder.add(count);
        }
    }

    /** The counts since the last take, the most frequent lines first; the counts start again from zero. */
    public Tally take() {
        lock.writeLock().lock();
        try {
            Tally tally = snapshot();
            since = clock.instant();
            errors.set(0);
            warnings.set(0);
            lines.clear();
            return tally;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** The counts since the last take, kept (a preview, a report sent by hand). */
    public Tally peek() {
        lock.writeLock().lock();
        try {
            return snapshot();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** A taken tally whose report did not go: its counts back, from its own start, for the next take. */
    public void giveBack(Tally tally) {
        lock.writeLock().lock();
        try {
            since = tally.since().isBefore(since) ? tally.since() : since;
            errors.addAndGet(tally.errors());
            warnings.addAndGet(tally.warnings());
            tally.lines().forEach(line -> add(line.text(), line.count()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Tally snapshot() {
        List<Line> now = lines.entrySet().stream().map(e -> new Line(e.getKey(), e.getValue().sum()))
                .sorted(Comparator.comparingLong(Line::count).reversed().thenComparing(Line::text)).toList();
        return new Tally(since, errors.get(), warnings.get(), now);
    }

    private static String shortName(String logger) {
        if (logger == null) {
            return "?";
        }
        int dot = logger.lastIndexOf('.');
        return dot < 0 ? logger : logger.substring(dot + 1);
    }

    /**
     * The kind of a line: the message's pattern before its arguments ("Party [{}]: … not counted", one kind for every party), the
     * numbers left in it as "#", its exception's class, on one line, cut — a code, a count or a stack trace would make each line a
     * kind of its own and fill the {@value #MAX_LINES}.
     */
    private static String message(ILoggingEvent event) {
        String text = event.getMessage() == null ? "" : event.getMessage();
        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) {
            text = text + " [" + thrown.getClassName() + "]";
        }
        text = DIGITS.matcher(WHITESPACE.matcher(text).replaceAll(" ").strip()).replaceAll("#");
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
