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
    /** The page "Ustawienia imprezy" ({@code /dj/settings}): what the DJ sets before a party, not during it. */
    public static final String REDIRECT_SETTINGS = "redirect:/dj/settings";
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
    /** The demo's panel (DemoController): only to look at — the page says so, its script sends nothing. */
    public static final String DEMO = "demo";
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
    /** Whether the AI has stopped judging the requests (AiHealthMonitor): the bar above the queue. */
    public static final String AI_DOWN = "aiDown";
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
    /** The dedication of a request sent back to the form (V38), as the guest typed it. */
    public static final String LAST_DEDICATION = "lastDedication";
    /** Whether the guests may add a dedication (V38, {@code PartySettingsEntity.dedicationsOn}). */
    public static final String DEDICATIONS_ON = "dedicationsOn";
    /** The newest dedications of the queue's songs (V38), by the song's id ({@code DjService.dedicationsOf}). */
    public static final String DEDICATIONS = "dedications";
    /** The queue's marks of the DJ's library (V41, V42): song id → "✓" / "?" / "✗"; none without a library or without Pro. */
    public static final String LIBRARY_MARKS = "libraryMarks";
    /** The settings' card of the DJ's library (V41): how many tracks (null: none uploaded) and the day of the upload. */
    public static final String LIBRARY_TRACKS = "libraryTracks";
    public static final String LIBRARY_UPLOADED = "libraryUploaded";
    /** The rekordbox playlist the library is (V42), null for a whole collection or an M3U. */
    public static final String LIBRARY_PLAYLIST = "libraryPlaylist";
    /** The largest file the server reads (DjLibraryController): library.js says "too large" before it reads or sends one. */
    public static final String LIBRARY_MAX_BYTES = "libraryMaxBytes";
    public static final String LIBRARY_MAX_MB = "libraryMaxMb";
    /** The party has a library its plan marks the queue by: the panel offers "Ukryj „Nie masz”" beside the search. */
    public static final String LIBRARY_ON = "libraryOn";
    /** The evening summary's songs not in the DJ's library (V42); null without a library or without the plan. */
    public static final String LIBRARY_GAPS = "libraryGaps";

    // --- Result Page Attributes ---
    public static final String RESPONSE = "response";

    // --- The hosts' lists (V29): the DJ's card and the hosts' page /h/{token} ---
    public static final String HOST_BLOCKED = "hostBlocked";
    public static final String HOST_WANTED = "hostWanted";
    /** The most entries a hosts' list keeps ({@code SongList.MAX_LINES}): the forms count against it (js/song-list-count.js). */
    public static final String SONG_LIST_MAX = "songListMax";
    /** The hosts' link to give them, or null when the DJ made none. */
    public static final String HOST_LINK = "hostLink";
    /** The day the hosts' link stops working ("14.11", V37). */
    public static final String HOST_LINK_UNTIL = "hostLinkUntil";
    /** How many entries each list has: the panel's row "Lista gospodarzy · 🚫 2 · ⭐ 1" (the design review, 2026-10-10). */
    public static final String HOST_BLOCKED_COUNT = "hostBlockedCount";
    public static final String HOST_WANTED_COUNT = "hostWantedCount";
    /** The hosts' list of wishes as the DJ's queue checks its songs for the ⭐ ({@code util.SongList}). */
    public static final String WANTED_SONGS = "wantedSongs";
    public static final String HOST_TOKEN = "hostToken";
    /** "Zapisane" on the hosts' page after a save. */
    public static final String HOST_SAVED = "hostSaved";
    /** Flash: a hosts' list was longer than this many entries — the first ones are kept, the page says the rest did not fit. */
    public static final String HOST_DROPPED = "hostDropped";

    // --- The party's staff (V30, V32) ---
    /** Whether the person owns the panel's party (else they are on its staff, {@link #ACCESS}). */
    public static final String IS_OWNER = "isOwner";
    /** What the person may do at the panel's party ({@code PartyStaffService.Access}: {@code may(…)}, {@code role()}). */
    public static final String ACCESS = "access";
    /** What the person may do, as the queue poll's {@code X-Panel-Access} says it: the page reloads when the poll says otherwise. */
    public static final String PANEL_ACCESS = "panelAccess";
    /** What the organiser's plan has (V35, {@code PlanService.View}): {@code plan.allows('TIPS')}, {@code plan.pro('HOST_LISTS')}. */
    public static final String PLAN = "plan";
    /** The panel's party as its staff sees it named ("DJ Koko", else the organiser's name, else its code). */
    public static final String PARTY_NAME = "partyName";
    /** The panels the person may switch between ({@code PartyStaffService.Panel}): their own, the parties they work at. */
    public static final String PANELS = "panels";
    /** Whether the person has a party of their own (else the menu offers "Załóż własną imprezę"). */
    public static final String HAS_OWN_PARTY = "hasOwnParty";
    /** The owner's list of the staff ({@code PartyStaffEntity}). */
    public static final String STAFF = "staff";
    /** The staff's invitation link, or null when the owner made none. */
    public static final String STAFF_LINK = "staffLink";
    /** The day the invitation link stops working ("14.11", V37). */
    public static final String STAFF_LINK_UNTIL = "staffLinkUntil";
    /** The roles and the permissions, in the order of the owner's list and checkboxes. */
    public static final String STAFF_ROLES = "staffRoles";
    public static final String STAFF_PERMISSIONS = "staffPermissions";
    /** The role "Zaproś" starts with (a joiner's before V33: "Obsługa kolejki"). */
    public static final String STAFF_DEFAULT_ROLE = "staffDefaultRole";
    /** "Zapisano" on the staff page after a change ({@code DjSessionHelper.Note} kind of message key), or null. */
    public static final String STAFF_SAVED = "staffSaved";
    /** The role the invitation link gives (V33) and its permissions — the link's form shows them picked. */
    public static final String STAFF_LINK_ROLE = "staffLinkRole";
    public static final String STAFF_LINK_PERMISSIONS = "staffLinkPermissions";
    /** The invitations by e-mail waiting (V34, {@code StaffInvitationEntity}), what came of the last "Zaproś", the places left of 10. */
    public static final String STAFF_INVITATIONS = "staffInvitations";
    public static final String STAFF_INVITE_RESULT = "staffInviteResult";
    public static final String STAFF_PLACES_LEFT = "staffPlacesLeft";
    /** A note for the panel ({@code DjSessionHelper.Note}: "Dołączono…", "Organizator usunął Twój dostęp…"), or null. */
    public static final String PANEL_NOTE = "panelNote";
    /** The invitation page: the party's name, its organiser, whether the person is logged in, the role they get, what went wrong. */
    public static final String JOIN_PARTY_NAME = "joinPartyName";
    public static final String JOIN_OWNER_NAME = "joinOwnerName";
    public static final String JOIN_TOKEN = "joinToken";
    public static final String JOIN_LOGGED_IN = "joinLoggedIn";
    public static final String JOIN_ROLE = "joinRole";
    /** The permissions the invitation gives (a role's set, or ticked one by one). */
    public static final String JOIN_PERMISSIONS = "joinPermissions";
    /** An invitation by e-mail (V34): its id — the page's "Dołącz" / "Nie, dziękuję" answer it; null for the link. */
    public static final String JOIN_INVITATION_ID = "joinInvitationId";
    public static final String JOIN_PROBLEM = "joinProblem";
    /** The screen for a TV (Next 7 c): the most wanted of the waiting songs, {@code GuestQueueService.mostWanted}. */
    public static final String TV_SONGS = "tvSongs";
}
