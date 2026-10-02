package com.scan2play.model;

import java.time.Instant;

/**
 * One line of the DJ's history — what played (or was rejected) and when: a guest's song request, or a track of the
 * background playlist. The two live in different tables; this is what puts them on one timeline.
 *
 * @param source      where it comes from
 * @param id          the {@code SongRequestEntity} id ({@link Source#GUEST}) or the id of the play log row,
 *                    {@code FallbackPlayEntity} ({@link Source#BACKGROUND}) — the id of one <em>play</em>, so the same
 *                    video in two rounds of a playlist has two ids
 * @param at          the moment of the event: when the song was played — or, for a rejected request and for one that
 *                    was played before V6 (no play time recorded), when it was requested; for a background track,
 *                    when the player took it
 * @param title       the song name the guest gave, or the video title of a background track ({@code youtu.be/<id>}
 *                    when it is unknown)
 * @param trackUrl    a link to the track; {@code null} when there is none
 * @param videoId     the YouTube video ID the embedded player can play, or {@code null} (a Spotify track, a YouTube
 *                    search URL)
 * @param style       the guest's song style as the AI judged it; {@code null} for a background track
 * @param decision    {@code played} or {@code rejected} (a background track is always {@code played})
 * @param djComment   the AI's comment; {@code null} for a background track
 * @param energyLevel the AI's energy rating; {@code null} for a background track
 * @param guestText   what the guest typed (V14), shown beside {@code title} when it says something else; {@code null} for a
 *                    background track, a DJ's pick and a request from before V14
 * @param votes       how many guests asked for the song (V15); {@code null} for a background track
 */
public record HistoryEntry(Source source, Long id, Instant at, String title, String trackUrl, String videoId,
                           String style, String decision, String djComment, Integer energyLevel, String guestText,
                           Integer votes) {

    /** An entry without the guest's words: a background track (or a request that has none). */
    public HistoryEntry(Source source, Long id, Instant at, String title, String trackUrl, String videoId,
                        String style, String decision, String djComment, Integer energyLevel) {
        this(source, id, at, title, trackUrl, videoId, style, decision, djComment, energyLevel, null);
    }

    /** An entry with the guest's words, one guest's: a request nobody else asked for. */
    public HistoryEntry(Source source, Long id, Instant at, String title, String trackUrl, String videoId,
                        String style, String decision, String djComment, Integer energyLevel, String guestText) {
        this(source, id, at, title, trackUrl, videoId, style, decision, djComment, energyLevel, guestText,
                source == Source.GUEST ? 1 : null);
    }

    public enum Source {
        GUEST,
        BACKGROUND
    }

    /**
     * An identifier that is unique across both tables and across plays: {@code G:<request id>} or
     * {@code B:<play id>}. The player script keeps the same string for the track it is playing ({@code next-track}
     * answers with the play id), so it can find its place in the list; one key per entry is what keeps ⏮ and ⏭ from
     * going in circles when the same video comes up twice.
     */
    public String key() {
        return (source == Source.GUEST ? "G:" : "B:") + id;
    }
}
