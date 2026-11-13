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
    /** The address of the DJ's logo (V45, {@code PartySettingsEntity.logoPath}) as the organiser's plan shows it, or null. */
    public static final String DJ_LOGO = "djLogo";
    /** The QR print as the DJ set it (V46): the party (its poster* fields), the language printed ("both", "pl", "en"), the editor shown. */
    public static final String POSTER = "poster";
    public static final String PRINT_LANG = "printLang";
    public static final String POSTER_EDIT = "posterEdit";
    /** The themes the print's editor offers (V47). */
    public static final String POSTER_THEMES = "posterThemes";
    /** The heading's fonts the print's editor offers (V51). */
    public static final String POSTER_FONTS = "posterFonts";

    // --- The look of the guests' pages (V59, "Motyw dla gości") ---
    /** The party's theme ({@code GuestTheme}): the guests' pages (data-guest-theme) and the card in "Ustawienia imprezy". */
    public static final String GUEST_THEME = "guestTheme";
    /** The themes the card offers, in their order. */
    public static final String GUEST_THEMES = "guestThemes";
    /** The card "Motyw dla gości" saved in the background: it says so ("✓ Zapisane"). */
    public static final String GUEST_THEME_SAVED = "guestThemeSaved";
    /** "Poranne podsumowanie" (V64): the organiser's switch of the morning e-mail, and the card saved in the background. */
    public static final String MORNING_SUMMARY = "morningSummary";
    public static final String MORNING_SUMMARY_SAVED = "morningSummarySaved";
    /** The card "Twoje logo": the largest file, in bytes and MB, and the longest side kept (V45, {@code LogoImage}). */
    public static final String LOGO_MAX_BYTES = "logoMaxBytes";
    public static final String LOGO_MAX_MB = "logoMaxMb";
    public static final String LOGO_MAX_SIDE = "logoMaxSide";
    /** The DJ's profiles (V24): https addresses on Instagram, Facebook, TikTok, or null. */
    public static final String INSTAGRAM_URL = "instagramUrl";
    public static final String FACEBOOK_URL = "facebookUrl";
    public static final String TIKTOK_URL = "tiktokUrl";
    /** The DJ's contact for bookings (V67): the settings' form — the address and the number as kept, or null. */
    public static final String BOOKING_EMAIL = "bookingEmail";
    public static final String BOOKING_PHONE = "bookingPhone";
    /** The same for the guests: {@code util.BookingContact} with its links, or null. */
    public static final String BOOKING = "booking";
    /** The DJ's tip link (V27): an https address on a service that takes tips, or null. */
    public static final String TIP_URL = "tipUrl";
    public static final String COMMENT_STYLE = "commentStyle";
    public static final String PARTY_CODE = "partyCode";
    public static final String IS_ACTIVE = "isActive";

    // --- Dashboard Attributes ---
    public static final String HISTORY = "history";
    public static final String HISTORY_HAS_MORE = "historyHasMore";
    /**
     * Whether the party has any history, whatever the filter shows: "Podsumowanie wieczoru" and "Wyczyść historię" are about all
     * of it, so a filter with nothing in it ("Zagrane" before a song played) does not take them away.
     */
    public static final String HISTORY_ANY = "historyAny";
    public static final String HISTORY_NEXT_LIMIT = "historyNextLimit";
    public static final String HISTORY_HEADING = "historyHeading";
    /** The demo's panel (DemoController): only to look at — the page says so, its script sends nothing. */
    public static final String DEMO = "demo";
    public static final String HISTORY_FILTER = "historyFilter";
    public static final String QR_CODE_BASE64 = "qrCodeBase64";
    public static final String PERMANENT_LINK = "permanentLink";
    /** The page to print the QR code on: "poster" (one A4 poster), "cards" (eight on a sheet, to cut out) or "tent" (V50, a table tent). */
    public static final String QR_LAYOUT = "qrLayout";
    /** The evening summary: the party's evenings with requests, the one shown, its summary (null when there is none). */
    public static final String EVENINGS = "evenings";
    public static final String EVENING = "evening";
    public static final String SUMMARY = "summary";
    /** The evening summary's version for the hosts (the couple, the client): without what is the DJ's own. */
    public static final String HOSTS_VIEW = "hostsView";
    /** The summary's evenings by month ({@code EveningSummaryService.Month}): the list's groups. */
    public static final String EVENING_MONTHS = "eveningMonths";
    /** The evenings "Ostatnie wieczory" compares, the shown one among them. */
    public static final String COMPARED_EVENINGS = "comparedEvenings";
    /** The evening's requests refused before the AI (V63), by reason: the DJ's version. */
    public static final String REFUSALS = "refusals";
    /** The DJ's title of the evening (V61); null: "Podsumowanie wieczoru". */
    public static final String EVENING_TITLE = "eveningTitle";
    /** The evening's hosts' link (V61, {@code /s/{token}}) while it works, and the day it stops. */
    public static final String SHARE_LINK = "shareLink";
    public static final String SHARE_UNTIL = "shareUntil";
    /** The summary opened by its hosts' link: no bar, no DJ's controls. */
    public static final String SHARED = "shared";
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
    /** The Free plan's AI checks an evening ({@code FreeAiLimit.Use}): its limit (0: none applies) and what this evening used. */
    public static final String FREE_AI = "freeAi";
    /** Yesterday's use of the Free plan's AI checks, for the organiser's note — only when some came unchecked, before tonight's first request; else null. */
    public static final String FREE_AI_YESTERDAY = "freeAiYesterday";
    /** The day the gift of Pro ends ("7.12."), to the organiser it gives Pro to; else null (PlanService.giftEndsFor). */
    public static final String PLAN_GIFT_UNTIL = "planGiftUntil";
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
    /** A second, quieter line under {@link #ERROR_MESSAGE}: what the guest can do instead (a dedication for a message). */
    public static final String ERROR_HINT = "errorHint";
    /** The text of a request sent back to the form (a mood, not a song). */
    public static final String LAST_REQUEST = "lastRequest";
    /** The dedication of a request sent back to the form (V38), as the guest typed it. */
    public static final String LAST_DEDICATION = "lastDedication";
    /**
     * The guest's song field asks which suggestions the DJ has (V58, {@code LibraryHintService.on}); the organiser's library card: the
     * switch as saved ({@code PartySettingsEntity.libraryHints}).
     */
    public static final String LIBRARY_HINTS = "libraryHints";
    /** "Podpowiedzi gości" just saved (a flash attribute of the redirect): "✓ Zapisane" beside the choice. */
    public static final String LIBRARY_HINTS_SAVED = "libraryHintsSaved";
    /** Whether the guests may add a dedication (V38, {@code PartySettingsEntity.dedicationsOn}). */
    public static final String DEDICATIONS_ON = "dedicationsOn";
    /** The newest dedications of the queue's songs (V38), by the song's id ({@code DjService.dedicationsOf}). */
    public static final String DEDICATIONS = "dedications";
    /**
     * The queue's marks of the DJ's library (V41, V42): song id → "✓" / "?" / "✗" with the tempo and the key of the DJ's track (V57,
     * {@code DjLibraryService.Match}); none without a library or without Pro.
     */
    public static final String LIBRARY_MARKS = "libraryMarks";
    /** The settings' card of the DJ's library (V41): how many tracks (null: none uploaded) and the day of the upload. */
    public static final String LIBRARY_TRACKS = "libraryTracks";
    public static final String LIBRARY_UPLOADED = "libraryUploaded";
    /** The rekordbox playlist the library is (V42), null for a whole collection or an M3U. */
    public static final String LIBRARY_PLAYLIST = "libraryPlaylist";
    /** A rekordbox library uploaded before V57 (keys alone): the card asks for it again, for the tempo and the key in the queue. */
    public static final String LIBRARY_OLD = "libraryOld";
    /** The largest file the server reads (DjLibraryController): library.js says "too large" before it reads or sends one. */
    public static final String LIBRARY_MAX_BYTES = "libraryMaxBytes";
    public static final String LIBRARY_MAX_MB = "libraryMaxMb";
    /** The party has a library its plan marks the queue by: the panel offers "Ukryj „Nie masz”" beside the search. */
    public static final String LIBRARY_ON = "libraryOn";
    /** The evening summary's songs not in the DJ's library (V42); null without a library or without the plan. */
    public static final String LIBRARY_GAPS = "libraryGaps";
    /** The library's gaps one a line: what the summary's "Kopiuj listę" copies. */
    public static final String LIBRARY_COPY = "libraryCopy";

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
    /** The hosts' "🚫 nie grać" as the queue checks its songs: a song the DJ added anyway is marked, not stopped (V44). */
    public static final String BLOCKED_SONGS = "blockedSongs";
    public static final String HOST_TOKEN = "hostToken";
    /** "Zapisane" on the hosts' page after a save. */
    public static final String HOST_SAVED = "hostSaved";
    /** Flash: a hosts' list was longer than this many entries — the first ones are kept, the page says the rest did not fit. */
    public static final String HOST_DROPPED = "hostDropped";
    /** Flash: the party's new code after "Zmień kod imprezy" (V49) — its card says it and leads to printing it again. */
    public static final String CODE_CHANGED = "codeChanged";

    // --- The venue's kiosk (V56) ---
    /** The kiosk's secret on its own page ({@code /k/{token}}): its forms and its list's address. */
    public static final String KIOSK_TOKEN = "kioskToken";
    /** The kiosk link to copy on "Ustawienia imprezy" ({@code https://…/k/{token}}), or null: none made. */
    public static final String KIOSK_LINK = "kioskLink";
    /** A settings card answered in the background (cards-in-place.js): it comes back unfolded. */
    public static final String CARD_SAVED = "cardSaved";
    /** The songs this guest's turn gave its 👍 at the kiosk. */
    public static final String KIOSK_MY_VOTES = "kioskMyVotes";
    /** The songs this guest's turn asked for at the kiosk (no 👍 on them). */
    public static final String KIOSK_MY_REQUESTS = "kioskMyRequests";
    /** The kiosk opened in a browser with a login: the page warns (a guest who leaves the kiosk would find the DJ's panel). */
    public static final String KIOSK_LOGGED_IN = "kioskLoggedIn";

    // --- When the party opens and closes by itself (V55) ---
    /** The plan as shown ({@code PartyScheduleService.View}): the panel's line, the card "Godziny imprezy" and its fields. */
    public static final String SCHEDULE = "schedule";
    /** Flash: the card "Godziny imprezy" saved — it says so, unfolded. */
    public static final String SCHEDULE_SAVED = "scheduleSaved";
    /** Flash: why the plan was not saved (a message key, {@code PartySchedule.Problem}). */
    public static final String SCHEDULE_ERROR = "scheduleError";
    /** Flash: the fields as typed when the plan was not saved — open day, open time, close day, close time. */
    public static final String SCHEDULE_TYPED = "scheduleTyped";
    /** The guest's "Prośby od soboty, 20:00" on the closed party's page and the TV screen; null without a planned open. */
    public static final String OPENS_AT = "opensAt";
    /** The TV screen's line in Polish and in English (it shows both under a Polish panel). */
    public static final String OPENS_AT_PL = "opensAtPl";
    public static final String OPENS_AT_EN = "opensAtEn";

    // --- The party's staff (V30, V32) ---
    /** Whether the person owns the panel's party (else they are on its staff, {@link #ACCESS}). */
    public static final String IS_OWNER = "isOwner";
    /** Whether the panel shows the welcome card ("Pierwszy raz? Napisz do nas", V48): to the organiser, until they close it. */
    public static final String WELCOME = "welcome";
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

    // --- "Życzenia przed imprezą" (V68) ---
    /** The guests' page of an event ({@code /w/{token}}): its secret for its form. */
    public static final String WISH_TOKEN = "wishToken";
    /** The event ({@code WishService.Event}): the guests' page its name and date, the DJ's page the one shown. */
    public static final String WISH_EVENT = "wishEvent";
    /** The event's date as the page's language writes it ("14 czerwca 2027"). */
    public static final String WISH_DATE = "wishDate";
    /** Whether the guests may write now (the event not closed, its day not passed, its ceiling not reached). */
    public static final String WISH_OPEN = "wishOpen";
    /** Flash: the song a guest's wish was kept as. */
    public static final String WISH_ADDED = "wishAdded";
    /** How many more songs this guest may write to the event (of {@code WishController.PER_GUEST}). */
    public static final String WISH_LEFT = "wishLeft";
    /** Flash: why a guest's wish was not kept, as the page says it. */
    public static final String WISH_NOTE = "wishNote";
    /** The party's events on the DJ's page ({@code WishService.Event}). */
    public static final String WISH_EVENTS = "wishEvents";
    /** Whether the party keeps as many events as it may ({@code WishService.MAX_EVENTS}): no new one until one is deleted. */
    public static final String WISH_KEPT_FULL = "wishKeptFull";
    public static final String WISH_MAX_EVENTS = "wishMaxEvents";
    /** The event's songs on the DJ's page ({@code DjWishesController.Row}): the named ones, and the ones to check apart. */
    public static final String WISH_ROWS = "wishRows";
    public static final String WISH_CHECK = "wishCheck";
    /** How many songs the event's wishes are, and how many of them the DJ's library has not. */
    public static final String WISH_SONGS = "wishSongs";
    public static final String WISH_MISSING = "wishMissing";
    /** The event's guests' link, to copy and to print. */
    public static final String WISH_LINK = "wishLink";
    /** Flash: what the DJ's page says after a form ({@code wishes.done.*}), and its argument. */
    public static final String WISH_DONE = "wishDone";
    public static final String WISH_DONE_ARG = "wishDoneArg";
    /** The most events open at once, the most wishes a guest writes, an event's name's length: the page says and the fields hold them. */
    public static final String WISH_MAX_OPEN = "wishMaxOpen";
    public static final String WISH_NAME_MAX = "wishNameMax";
    /** The days the date field offers: today and the last one ({@code WishService.MAX_DAYS_AHEAD}). */
    public static final String WISH_DATE_MIN = "wishDateMin";
    public static final String WISH_DATE_MAX = "wishDateMax";
    /** The QR print of an event's guests' link (an invitation): the event; null for the party's own code. */
    public static final String WISH_PRINT = "wishPrint";
}
