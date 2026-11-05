package com.scan2play.model;

/**
 * Why a guest's request was refused before the AI judged a song (V63) — never saved as a request, only counted for the evening
 * summary: no song, no word of the guest's.
 */
public enum RefusalReason {
    /** A song on the hosts' "nie grać" list (V29). */
    HOSTS_BLOCKED,
    /** A song the DJ skipped within the last two hours. */
    SKIPPED_BY_DJ,
    /** A mood or a message, not a song: the guest was asked for a title. */
    NOT_A_SONG
}
