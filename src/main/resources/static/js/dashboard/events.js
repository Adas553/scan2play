/**
 * The events the dashboard's modules tell each other with — the whole contract between them in one place, instead of functions put
 * on `window` and called after a `typeof … === 'function'`, where a typo switches a feature off without a word. An event is a
 * CustomEvent on `document`; nobody listening is fine.
 */
export const EVENTS = Object.freeze({
    /**
     * The DJ changed the guest queue here (a song played, skipped or put back, the queue cleared). Dashboard: fetch the queue now,
     * not in 3 s.
     */
    GUEST_QUEUE_CHANGED: 's2p:guest-queue-changed',
    /** The DJ changed the history here (a skipped request put back in the queue). The History tab: fetch the list again. */
    HISTORY_CHANGED: 's2p:history-changed'
});

export function emit(name, detail) {
    document.dispatchEvent(new CustomEvent(name, { detail: detail || {} }));
}

export function on(name, handler) {
    document.addEventListener(name, function (e) { handler(e.detail || {}); });
}
