package com.scan2play.model;

/**
 * A command the DJ gives to the dashboard window that plays, from any window of the party (the phone as a remote
 * control of the computer): it travels to the window that plays inside the answer to that window's next lease report.
 */
public enum PlayerCommand {
    /** Skip to the next track: what {@code next-track} hands out now, whatever the player is doing. */
    NEXT,
    /**
     * Back, like a normal player: restart the track that has played for more than a few seconds, otherwise play the
     * track that played before it (and, pressed again, the one before that). The single ⏮ of the window that plays sends
     * nothing; a window that does not play has {@link #PREVIOUS_TRACK} and {@link #RESTART} instead — this one is still
     * accepted, for a dashboard page that was opened before they existed.
     */
    PREVIOUS,
    /** The previous track, always — no restart first: the "back" button of a window that does not play. */
    PREVIOUS_TRACK,
    /** The track that runs starts again from the beginning, however long it has played: "from the start" of a window that does not play. */
    RESTART,
    /** Pause the music (a no-op when it is paused already). Explicit rather than a toggle, so a stale button cannot invert it. */
    PAUSE,
    /** Carry on after a pause (a no-op when the music is playing). */
    RESUME
}
