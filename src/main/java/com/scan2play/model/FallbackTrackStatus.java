package com.scan2play.model;

/**
 * Lifecycle of a server-side fallback (background music) track — see {@code FallbackTrackEntity}.
 */
public enum FallbackTrackStatus {
    /** Imported and waiting to be played. */
    QUEUED,
    /** Already played (kept as history). */
    PLAYED,
    /** Invalidated because the DJ changed or cleared the fallback playlist before it was played. */
    CANCELLED
}
