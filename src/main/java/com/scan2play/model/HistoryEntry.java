package com.scan2play.model;

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
 * @param energyLevel the AI's energy rating
 * @param guestText   what the guest typed (V14), always shown under {@code title} (the DJ checks the AI); {@code null} for a
 *                    request from before V14
 * @param votes       how many guests asked for the song (V15)
 */
public record HistoryEntry(Long id, Instant at, String title, String trackUrl, String style, String decision, String djComment,
                           Integer energyLevel, String guestText, Integer votes) {

    /** An entry with the guest's words, one guest's: a request nobody else asked for. */
    public HistoryEntry(Long id, Instant at, String title, String trackUrl, String style, String decision, String djComment,
                        Integer energyLevel, String guestText) {
        this(id, at, title, trackUrl, style, decision, djComment, energyLevel, guestText, 1);
    }
}
