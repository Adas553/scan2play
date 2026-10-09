package com.scan2play.controller;

/**
 * Centralizes constants for model attribute names and redirect paths used across controllers.
 * This prevents typos and makes refactoring easier.
 */
public final class ViewAttributes {

    private ViewAttributes() {
        // Prevent instantiation
    }

    // --- Redirect Paths ---
    public static final String REDIRECT_DASHBOARD = "redirect:/dj/dashboard";
    public static final String REDIRECT_HOME = "redirect:/";

    // --- Common Attributes ---
    public static final String GLOBAL_VIBE = "globalVibe";
    public static final String VIBE_NOTE = "vibeNote";
    public static final String DJ_NAME = "djName";
    /** The DJ's profiles (V24): https addresses on Instagram, Facebook, TikTok, or null. */
    public static final String INSTAGRAM_URL = "instagramUrl";
    public static final String FACEBOOK_URL = "facebookUrl";
    public static final String TIKTOK_URL = "tiktokUrl";
    /** The DJ's tip link (V27): an https address on a service that takes tips, or null. */
    public static final String TIP_URL = "tipUrl";
    public static final String COMMENT_STYLE = "commentStyle";
    public static final String PARTY_CODE = "partyCode";
    public static final String IS_ACTIVE = "isActive";

    // --- Dashboard Attributes ---
    public static final String HISTORY = "history";
    public static final String HISTORY_HAS_MORE = "historyHasMore";
    public static final String HISTORY_NEXT_LIMIT = "historyNextLimit";
    public static final String HISTORY_HEADING = "historyHeading";
    public static final String HISTORY_FILTER = "historyFilter";
    public static final String QR_CODE_BASE64 = "qrCodeBase64";
    public static final String PERMANENT_LINK = "permanentLink";
    /** The page to print the QR code on: "poster" (one A4 poster) or "cards" (eight cards to cut out). */
    public static final String QR_LAYOUT = "qrLayout";
    /** The evening summary: the party's evenings with requests, the one shown, its summary (null when there is none). */
    public static final String EVENINGS = "evenings";
    public static final String EVENING = "evening";
    public static final String SUMMARY = "summary";
    public static final String REQUEST_LIMIT = "requestLimit";
    public static final String COOLDOWN_MINUTES = "cooldownMinutes";
    public static final String DUPLICATE_CHECK_WINDOW = "duplicateCheckWindow";
    public static final String SERVER_LIMIT_PER_NETWORK = "serverLimitPerNetwork";
    public static final String SERVER_LIMIT_WINDOW_MINUTES = "serverLimitWindowMinutes";
    public static final String SERVER_LIMIT_PER_PARTY = "serverLimitPerParty";
    public static final String PARTY_REQUESTS_USED = "partyRequestsUsed";
    public static final String BUSIEST_NETWORK_REQUESTS_USED = "busiestNetworkRequestsUsed";
    public static final String PARTY_LIMIT_REACHED = "partyLimitReached";
    /** The VAPID public key (base64url) for the switch of notifications on this device; null = notifications are off on the server. */
    public static final String PUSH_PUBLIC_KEY = "pushPublicKey";

    // --- Index/Guest Attributes ---
    /** The guests' requests (one list, by votes) and whether the guest's own song waits ({@code GuestQueueService.GuestQueue}). */
    public static final String GUEST_QUEUE = "guestQueue";
    /** A note over the guest's page after a 👍 that did not count (the song gone, the network's limit). */
    public static final String VOTE_NOTE = "voteNote";
    /** The song of a 👍 as it is now (its new votes), or null when it no longer waits. */
    public static final String VOTE_SONG = "voteSong";
    public static final String ERROR_MESSAGE = "errorMessage";
    /** The text of a request sent back to the form (a mood, not a song). */
    public static final String LAST_REQUEST = "lastRequest";

    // --- Result Page Attributes ---
    public static final String RESPONSE = "response";

    // --- The hosts' lists (V29): the DJ's card and the hosts' page /h/{token} ---
    public static final String HOST_BLOCKED = "hostBlocked";
    public static final String HOST_WANTED = "hostWanted";
    /** The hosts' link to give them, or null when the DJ made none. */
    public static final String HOST_LINK = "hostLink";
    /** The hosts' list of wishes as the DJ's queue checks its songs for the ⭐ ({@code util.SongList}). */
    public static final String WANTED_SONGS = "wantedSongs";
    public static final String HOST_TOKEN = "hostToken";
    /** "Zapisane" on the hosts' page after a save. */
    public static final String HOST_SAVED = "hostSaved";

    // --- The party's staff (V30) ---
    /** Whether the person owns the panel's party: a staff member sees the queue and the history only. */
    public static final String IS_OWNER = "isOwner";
    /** The panel's party as its staff sees it named ("DJ Koko", else its code). */
    public static final String PARTY_NAME = "partyName";
    /** The panels the person may switch between ({@code PartyStaffService.Panel}): their own, the parties they work at. */
    public static final String PANELS = "panels";
    /** The owner's list of the staff ({@code PartyStaffEntity}). */
    public static final String STAFF = "staff";
    /** The staff's invitation link, or null when the owner made none. */
    public static final String STAFF_LINK = "staffLink";
    /** The message key of what an invitation link did when the person came back from the login ("Dołączono…"), or null. */
    public static final String STAFF_JOIN_NOTE = "staffJoinNote";
    /** The invitation page: the party's name and where its button leads. */
    public static final String JOIN_PARTY_NAME = "joinPartyName";
    public static final String JOIN_URL = "joinUrl";
}
