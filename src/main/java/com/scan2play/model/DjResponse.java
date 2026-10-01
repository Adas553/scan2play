package com.scan2play.model;


/**
 * Represents the DJ's response to a song request.
 *
 * @param decision    The decision made by the DJ (e.g., "accepted", "rejected").
 * @param comment     A short comment or feedback from the DJ.
 * @param songName    The confirmed name of the song.
 * @param energyLevel The energy level of the song on a scale of 1-10.
 * @param requestKind What the guest typed, as the AI reads it: {@value #KIND_TITLE}, {@value #KIND_ARTIST}, {@value #KIND_LYRICS}
 *                    or {@value #KIND_MOOD}; null when the AI did not say (an older answer, an error). For {@value #KIND_LYRICS}
 *                    the song is searched by the guest's own words, not by the AI's name of it (see SongEvaluationService).
 * @param requestId   The id of the saved song request; null when nothing was saved (and in the AI's own answer).
 */
public record DjResponse(
        String decision,
        String comment,
        String songName,
        int energyLevel,
        String requestKind,
        Long requestId
) {

    public static final String KIND_TITLE = "title";
    public static final String KIND_ARTIST = "artist";
    public static final String KIND_LYRICS = "lyrics";
    public static final String KIND_MOOD = "mood";

    /** A response without the kind of the request (an error, a test). */
    public DjResponse(String decision, String comment, String songName, int energyLevel) {
        this(decision, comment, songName, energyLevel, null, null);
    }

    /** The AI's answer: not saved yet. */
    public DjResponse(String decision, String comment, String songName, int energyLevel, String requestKind) {
        this(decision, comment, songName, energyLevel, requestKind, null);
    }

    /** Whether the guest typed a fragment of the lyrics, as the AI reads it. */
    public boolean isLyrics() {
        return KIND_LYRICS.equalsIgnoreCase(requestKind);
    }

    /** Whether the AI read the request as a mood — in the song mode that means the guest chose the wrong mode. */
    public boolean isMood() {
        return KIND_MOOD.equalsIgnoreCase(requestKind);
    }

    /** The same response under another song name (what the found video is called). */
    public DjResponse withSongName(String name) {
        return new DjResponse(decision, comment, name, energyLevel, requestKind, requestId);
    }

    /** The same response once the request is saved under this id. */
    public DjResponse withRequestId(Long id) {
        return new DjResponse(decision, comment, songName, energyLevel, requestKind, id);
    }
}
