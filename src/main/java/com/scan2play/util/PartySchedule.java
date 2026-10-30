package com.scan2play.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * The rules of a party's planned open and close (V55, "Godziny imprezy"), without a clock or a database of their own — the service
 * gives them the moment it is now. The moments are kept as {@link Instant}; the DJ types them, and reads them, in Polish time
 * ({@link Times#DISPLAY_ZONE}).
 * <p>
 * The plan never holds a moment that would change nothing ({@link #settle}): an open while the party will be open by then, a close
 * while it will be closed. A manual open or close against the plan wins until the next planned moment — the moment it made pointless
 * goes, the other stays.
 */
public final class PartySchedule {

    /** How far ahead a moment may be set: a party of the coming months, not a typo of the year. */
    public static final int MAX_DAYS_AHEAD = 365;

    /** Why a plan was not saved — each the key {@code schedule.error.<name in lower case>} of the messages. */
    public enum Problem {
        /** A time without a day, or a day without a time. */
        INCOMPLETE,
        /** Not a date or not a time. */
        NOT_A_MOMENT,
        /** The moment has passed (or is this very minute). */
        PAST,
        /** More than {@link #MAX_DAYS_AHEAD} days ahead. */
        TOO_FAR,
        /** The open and the close at the same minute. */
        SAME_MOMENT,
        /** An open while the party will be open anyway (it is open, and no close comes first). */
        OPEN_WHILE_OPEN,
        /** A close while the party will be closed anyway. */
        CLOSE_WHILE_CLOSED;

        public String messageKey() {
            return "schedule.error." + name().toLowerCase();
        }
    }

    /** A plan the DJ cannot have, and why. */
    public static class Refused extends RuntimeException {
        private final Problem problem;

        public Refused(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /** The planned moments; either may be null. */
    public record Plan(Instant openAt, Instant closeAt) {
        public static final Plan NONE = new Plan(null, null);
    }

    private PartySchedule() {
    }

    /**
     * The moment the form's day ("2026-10-31") and time ("20:00") name in Polish time; null when both are empty. A time the clocks
     * skip in spring (02:30 on the last Sunday of March) is the same time after the change, 03:30; one they go through twice in autumn
     * is the first of the two.
     */
    public static Instant moment(String date, String time) {
        boolean noDate = date == null || date.isBlank();
        boolean noTime = time == null || time.isBlank();
        if (noDate && noTime) {
            return null;
        }
        if (noDate || noTime) {
            throw new Refused(Problem.INCOMPLETE);
        }
        try {
            LocalDateTime local = LocalDateTime.of(LocalDate.parse(date.trim()), LocalTime.parse(time.trim()))
                    .truncatedTo(ChronoUnit.MINUTES);
            return local.atZone(Times.DISPLAY_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            throw new Refused(Problem.NOT_A_MOMENT);
        }
    }

    /**
     * Checks a plan the DJ sets for a party open ({@code active}) or not, at {@code now}: each moment still to come and not too far,
     * not both at once, and neither one that would change nothing.
     *
     * @throws Refused why not
     */
    public static void check(Plan plan, boolean active, Instant now) {
        for (Instant at : new Instant[]{plan.openAt(), plan.closeAt()}) {
            if (at == null) {
                continue;
            }
            if (!at.isAfter(now)) {
                throw new Refused(Problem.PAST);
            }
            if (at.isAfter(now.plus(MAX_DAYS_AHEAD, ChronoUnit.DAYS))) {
                throw new Refused(Problem.TOO_FAR);
            }
        }
        if (plan.openAt() != null && plan.openAt().equals(plan.closeAt())) {
            throw new Refused(Problem.SAME_MOMENT);
        }
        Plan settled = settle(plan, active);
        if (plan.openAt() != null && settled.openAt() == null) {
            throw new Refused(Problem.OPEN_WHILE_OPEN);
        }
        if (plan.closeAt() != null && settled.closeAt() == null) {
            throw new Refused(Problem.CLOSE_WHILE_CLOSED);
        }
    }

    /**
     * The plan without the moments that would change nothing for a party open ({@code active}) or not: the moments in their order,
     * each one that would leave the party as the one before left it is dropped. A manual close of an open party with a close planned
     * before the next open drops that close (it has happened); the open stays.
     */
    public static Plan settle(Plan plan, boolean active) {
        Instant open = plan.openAt();
        Instant close = plan.closeAt();
        boolean openFirst = open != null && (close == null || open.isBefore(close));
        if (openFirst) {
            // open, then (maybe) close: the open changes nothing if the party is open already — then the close is the first
            return active ? new Plan(null, close) : plan;
        }
        if (close != null) {
            // close, then (maybe) open
            return active ? plan : new Plan(open, null);
        }
        return plan;
    }
}
