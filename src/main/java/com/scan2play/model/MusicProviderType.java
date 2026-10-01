package com.scan2play.model;

/** The kind of a party: where its music comes from. */
public enum MusicProviderType {
    SPOTIFY,
    YOUTUBE,
    /** The DJ plays from their own software: the party only collects the guests' requests (no player, no Spotify). */
    REQUESTS_ONLY
}
