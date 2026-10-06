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
    /** The requests sent lately and whether the guest's own song waits ({@code GuestQueueService.GuestQueue}). */
    public static final String GUEST_QUEUE = "guestQueue";
    public static final String ERROR_MESSAGE = "errorMessage";
    /** The text of a request sent back to the form (a mood, not a song). */
    public static final String LAST_REQUEST = "lastRequest";

    // --- Result Page Attributes ---
    public static final String RESPONSE = "response";
}
