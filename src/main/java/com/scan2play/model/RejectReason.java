package com.scan2play.model;

/**
 * Why the AI rejected a request (V62), as the code knows it when the request is saved — the prompt is not asked for it: the song
 * played lately (it was on the list of the recently played songs the AI was given), or any other refusal, judged against the vibe.
 * "Podsumowanie wieczoru" counts them apart.
 */
public enum RejectReason {
    /** The song played lately: the DJ's duplicate window. */
    DUPLICATE,
    /** Any other refusal: the AI found it against the evening's vibe or the DJ's note. */
    OFF_VIBE
}
