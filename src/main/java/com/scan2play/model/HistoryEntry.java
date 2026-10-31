package com.scan2play.model;

import com.scan2play.util.SongNames;

import java.time.Instant;

/**
 * One line of the DJ's history — a guest's song request that played or was rejected, and when.
 *
 * @param id          the {@code SongRequestEntity} id
 * @param at          the moment of the event: when the song was played — or, for a rejected request and for one that
 *                    was played before V6 (no play time recorded), when it was requested
 * @param title       the song name
 * @param trackUrl    the song's "🔍 Podejrzyj" link (YouTube's search results); {@code null} when there is none
 * @param style       the guest's song style as the AI judged it
 * @param decision    {@code played} or {@code rejected}
 * @param djComment   the AI's comment
 * @param guestText   what the guest typed (V14), always shown under {@code title} (the DJ checks the AI); {@code null} for a
 *                    request from before V14
 * @param votes       how many guests asked for the song (V15)
 * @param skippedAt   when the DJ skipped it ("Pomiń", V20); {@code null} for every other entry
 * @param clearedAt   when the DJ cleared it with the whole queue ("Wyczyść kolejkę", V25); {@code null} for every other entry
 * @param requestNumber the song's number at the party (V28, "#27"); {@code null} for one the AI rejected
 * @param tips        how many tips the DJ counted for it (V28, "💸")
 * @param addedByDj   whether the DJ or the staff added it in the panel (V44): no guest's words, no AI's verdict
 * @param viaKiosk    whether a guest sent it at the venue's kiosk (V56): marked as in the queue
 */
public record HistoryEntry(Long id, Instant at, String title, String trackUrl, String style, String decision, String djComment,
                           String guestText, Integer votes, Instant skippedAt, Instant clearedAt,
                           Integer requestNumber, int tips, boolean addedByDj, boolean viaKiosk) {

    /** An entry not from the kiosk. */
    public HistoryEntry(Long id, Instant at, String title, String trackUrl, String style, String decision, String djComment,
                        String guestText, Integer votes, Instant skippedAt, Instant clearedAt, Integer requestNumber, int tips,
                        boolean addedByDj) {
        this(id, at, title, trackUrl, style, decision, djComment, guestText, votes, skippedAt, clearedAt, requestNumber, tips, addedByDj,
                false);
    }

    /** An entry a guest asked for (not one the DJ added). */
    public HistoryEntry(Long id, Instant at, String title, String trackUrl, String style, String decision, String djComment,
                        String guestText, Integer votes, Instant skippedAt, Instant clearedAt, Integer requestNumber, int tips) {
        this(id, at, title, trackUrl, style, decision, djComment, guestText, votes, skippedAt, clearedAt, requestNumber, tips, false);
    }

    /** An entry without a number or tips (from before V28, a test). */
    public HistoryEntry(Long id, Instant at, String title, String trackUrl, String style, String decision, String djComment,
                        String guestText, Integer votes, Instant skippedAt, Instant clearedAt) {
        this(id, at, title, trackUrl, style, decision, djComment, guestText, votes, skippedAt, clearedAt, null, 0);
    }

    /** An entry the DJ neither skipped nor cleared. */
    public HistoryEntry(Long id, Instant at, String title, String trackUrl, String style, String decision, String djComment,
                        String guestText, Integer votes) {
        this(id, at, title, trackUrl, style, decision, djComment, guestText, votes, null, null);
    }

    /** Whether the DJ skipped this request — the history offers to put it back in the queue ("↩ Przywróć"). */
    public boolean skippedByDj() {
        return skippedAt != null && "rejected".equals(decision);
    }

    /** Whether the DJ cleared this request with the whole queue — the history says so in place of the AI's verdict. */
    public boolean clearedByDj() {
        return clearedAt != null && "rejected".equals(decision);
    }

    /** Whether the AI's song has none of the guest's words in it: the history marks it "⚠ Sprawdź" ({@code SongNames.sharesNoWord}). */
    public boolean needsCheck() {
        return SongNames.sharesNoWord(guestText, title);
    }

    /** An entry with the guest's words, one guest's: a request nobody else asked for. */
    public HistoryEntry(Long id, Instant at, String title, String trackUrl, String style, String decision, String djComment,
                        String guestText) {
        this(id, at, title, trackUrl, style, decision, djComment, guestText, 1);
    }
}
