/**
 * The events the dashboard's modules and the player (youtube-autopilot.js) tell each other with — the whole contract between them
 * in one place, instead of functions put on `window` and called after a `typeof … === 'function'`, where a typo switches a
 * feature off without a word. An event is a CustomEvent on `document`; nobody listening is fine — a Spotify party's
 * dashboard has no player. wake-lock.js, a classic script, listens to PLAYBACK_MODE by its name.
 */
export const EVENTS = Object.freeze({
    /** The window's Auto-Pilot setting is now {mode: 'AUTO'|'MANUAL'} and has changed. Player: start if idle; wake lock. */
    PLAYBACK_MODE: 's2p:playback-mode',
    /** A lease answer said the party's setting is {mode, sentAt} (sentAt: when the report left). Dashboard: follow it. */
    PLAYBACK_MODE_REPORTED: 's2p:playback-mode-reported',
    /** The guest queue table was replaced by a poll. Player: a song may be waiting. */
    GUEST_QUEUE_UPDATED: 's2p:guest-queue-updated',
    /** The DJ changed the guest queue here (a song played, skipped or picked). Dashboard: fetch the queue now, not in 3 s. */
    GUEST_QUEUE_CHANGED: 's2p:guest-queue-changed',
    /** The DJ saved another background playlist here. Player: drop the old track, start from the new one. */
    FALLBACK_PLAYLIST_SAVED: 's2p:fallback-playlist-saved',
    /** The DJ cleared the background playlist here (the server already has). Player: stop the background track. */
    FALLBACK_PLAYLIST_CLEARED: 's2p:fallback-playlist-cleared',
    /** The "up next" list may have changed (the player took a track, or another window changed it). Dashboard: fetch it. */
    FALLBACK_QUEUE_STALE: 's2p:fallback-queue-stale',
    /** The "up next" list on the page is now {version}. Player: do not fetch it again for the same state. */
    FALLBACK_QUEUE_VERSION: 's2p:fallback-queue-version'
});

export function emit(name, detail) {
    document.dispatchEvent(new CustomEvent(name, { detail: detail || {} }));
}

export function on(name, handler) {
    document.addEventListener(name, function (e) { handler(e.detail || {}); });
}
