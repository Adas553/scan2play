package com.scan2play.model;

/**
 * What a dashboard window wants from the "which window plays" lease when it reports in
 * ({@code POST /dj/dashboard/player-lease}).
 */
public enum PlayerLeaseMode {
    /** Renew the lease if this window holds it; take it only when nobody holds it. What a playing or a freshly opened window sends. */
    CLAIM,
    /** Only report who holds the lease; never take it. What a window that has been told another one plays keeps sending. */
    WATCH,
    /** The DJ pressed "play on this device": take the lease from whoever holds it. */
    TAKE_OVER
}
