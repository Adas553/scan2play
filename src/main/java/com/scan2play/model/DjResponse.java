package com.scan2play.model;


/**
 * Represents the DJ's response to a song request.
 *
 * @param decision    The decision made by the DJ (e.g., "accepted", "rejected").
 * @param comment     A short comment or feedback from the DJ.
 * @param songName    The confirmed name of the song.
 * @param energyLevel The energy level of the song on a scale of 1-10.
 * @param requestKind What the guest typed, as the AI reads it: {@value #KIND_TITLE}, {@value #KIND_ARTIST}, {@value #KIND_LYRICS}
 *                    or {@value #KIND_MOOD}; {@value #KIND_UNCHECKED} when the AI could not be asked and the request went to the
 *                    DJ unchecked (a requests-only party); null when the AI did not say (an older answer, an error). For {@value #KIND_LYRICS}
 *                    the song is searched by the guest's own words, not by the AI's name of it (see SongEvaluationService).
 * @param requestId   The id of the saved song request; null when nothing was saved (and in the AI's own answer). For a vote, the
 *                    id of the waiting song it was counted on.
 * @param votes       How many guests asked for the song now (V15): 1 for a new request, more when the same song already waited
 *                    and this request was counted on it ({@link #isVote()}); 0 when nothing was saved.
 * @param ownSong     The same song already waits as the guest's own request: nothing was saved or counted.
 */
public record DjResponse(
        String decision,
        String comment,
        String songName,
        int energyLevel,
        String requestKind,
        Long requestId,
        int votes,
        boolean ownSong
) {

    public static final String KIND_TITLE = "title";
    public static final String KIND_ARTIST = "artist";
    public static final String KIND_LYRICS = "lyrics";
    public static final String KIND_MOOD = "mood";
    public static final String KIND_UNCHECKED = "unchecked";

    /** A response before it is saved, or one that was saved as a new request (votes untold). */
    public DjResponse(String decision, String comment, String songName, int energyLevel, String requestKind, Long requestId) {
        this(decision, comment, songName, energyLevel, requestKind, requestId, 0, false);
    }

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

    /** Whether the AI read the request as a mood, not a song — the guest is asked for a song (the DJ sets the mood). */
    public boolean isMood() {
        return KIND_MOOD.equalsIgnoreCase(requestKind);
    }

    /** Whether the request went to the DJ without the AI's check (the AI could not be asked). */
    public boolean isUnchecked() {
        return KIND_UNCHECKED.equals(requestKind);
    }

    /** Whether the request was counted as one more vote on the same song waiting in the queue. */
    public boolean isVote() {
        return votes > 1 && !ownSong;
    }

    /** The same response under another song name (what the found video is called). */
    public DjResponse withSongName(String name) {
        return new DjResponse(decision, comment, name, energyLevel, requestKind, requestId, votes, ownSong);
    }

    /** The same request with another verdict: the one a song waiting in the queue was taken with. */
    public DjResponse withVerdict(String newDecision, String newComment, int newEnergyLevel) {
        return new DjResponse(newDecision, newComment, songName, newEnergyLevel, requestKind, requestId, votes, ownSong);
    }

    /** The same response once the request is saved under this id. */
    public DjResponse withRequestId(Long id) {
        return new DjResponse(decision, comment, songName, energyLevel, requestKind, id, votes, ownSong);
    }

    /**
     * The same response once it is in the queue: a new request, a vote on the same song that waited (under that song's name, id
     * and votes), or the guest's own song that already waited ({@code ownSong}).
     */
    public DjResponse savedAs(Long id, String name, int votesNow, boolean guestsOwnSong) {
        return new DjResponse(decision, comment, name, energyLevel, requestKind, id, votesNow, guestsOwnSong);
    }
}
