package com.scan2play.service;

import lombok.Getter;

/**
 * The fallback playlist could not be imported. Thrown <b>before</b> anything is written, so the
 * party's existing fallback tracks are never touched by a failed import.
 * <p>
 * Deliberately carries no cause: the underlying HTTP client exceptions can contain the request
 * URL, which includes the YouTube API key.
 */
@Getter
public class FallbackImportException extends RuntimeException {

    public enum Reason {
        /** {@code youtube.api-key} / {@code YOUTUBE_API_KEY} is not configured. */
        NO_API_KEY,
        /** The input is not a syntactically valid playlist ID. */
        INVALID_PLAYLIST,
        /** The YouTube Data API call failed (quota, network, unknown or private playlist, ...). */
        API_ERROR,
        /** The playlist exists but has no public, embeddable videos. */
        NO_PLAYABLE_TRACKS,
        /**
         * A YouTube Mix ({@code list=RD…}): made up by YouTube for one viewer, not given out by the Data API. Refused before the link
         * is saved (the party keeps its playlist).
         */
        YOUTUBE_MIX
    }

    private final Reason reason;

    public FallbackImportException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
