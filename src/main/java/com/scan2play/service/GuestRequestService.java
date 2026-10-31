package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.DjResponse;
import com.scan2play.model.VibeType;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static com.scan2play.service.DjService.DECISION_REJECTED;

/**
 * A guest's song request to an open party, the same way from the party page ({@code GuestController}) and from the venue's kiosk
 * (V56, {@code KioskController}): nothing typed is refused before any limit is used; then the guest's own limit (the DJ's setting,
 * per session — at the kiosk per guest's turn), then the server's — the address's at the party or, at the kiosk, the kiosk's own
 * (the tablet shares the venue's Wi-Fi with the phones), the party's day either way —, the AI; a request that came to nothing gives
 * the guest's limit back; the guest's song is remembered in the session. The callers check the party is open and say the answer.
 */
@Service
@RequiredArgsConstructor
public class GuestRequestService {

    private final SongEvaluationService songEvaluationService;
    private final GuestSessionService guestSessionService;
    private final GuestVoteService guestVoteService;
    private final GuestRequestLimiter guestRequestLimiter;
    private final MessageSource messageSource;
    private final SiteCounter siteCounter;

    /** What became of a guest's request: refused by a limit (the message's key and arguments), a mood, or the AI's answer. */
    public sealed interface Sent {
        record Refused(String messageKey, Object... args) implements Sent {
        }

        /** A mood, not a song: nothing saved; the form again with what was typed (the dedication as taken). */
        record Mood(String songName, String dedication) implements Sent {
        }

        record Answered(DjResponse response) implements Sent {
        }
    }

    /** A request from the party page, from the client address {@code clientIp} (read on the request's thread). */
    public Sent fromParty(String partyCode, PartySettingsEntity settings, String songName, String dedication, HttpSession session,
                          String clientIp, Locale locale) {
        return send(partyCode, settings, songName, dedication, session, clientIp, locale);
    }

    /** A request from the party's kiosk: the kiosk's own limit in place of an address's; the request is marked as the kiosk's. */
    public Sent fromKiosk(String partyCode, PartySettingsEntity settings, String songName, String dedication, HttpSession session,
                          Locale locale) {
        return send(partyCode, settings, songName, dedication, session, null, locale);
    }

    /** {@code clientIp} null: the kiosk. */
    private Sent send(String partyCode, PartySettingsEntity settings, String songName, String dedication, HttpSession session,
                      String clientIp, Locale locale) {
        boolean kiosk = clientIp == null;
        // Nothing typed (only spaces get past the form's "required"; a POST may skip the form): back to the form before
        // any limit is used or the AI is asked — with the AI down it would be an empty row in the DJ's queue
        if (SongEvaluationService.asTyped(songName).isEmpty()) {
            return new Sent.Refused("guest.error.empty");
        }
        siteCounter.count(SiteCounter.Event.GUEST_REQUEST);   // a guest's request sent, whatever comes of it (V40)
        // The guest's own limit first (the DJ's setting), then the server's limits that do not need the cookie.
        // Both count the request before the evaluation.
        Optional<Long> waitTimeSeconds = guestSessionService.tryAcquire(session, partyCode, settings);
        if (waitTimeSeconds.isPresent()) {
            return new Sent.Refused("guest.error.rate_limit", settings.getRequestLimit(), waitText(waitTimeSeconds.get()));
        }
        Optional<GuestRequestLimiter.Refusal> refusal = kiosk ? guestRequestLimiter.tryAcquireKiosk(partyCode)
                : guestRequestLimiter.tryAcquire(clientIp, partyCode);
        if (refusal.isPresent()) {
            // the network's (the kiosk's) or the party's limit refused it, not the guest: their own limit is given back — it was
            // used up for a request never sent
            guestSessionService.giveBack(session, partyCode);
            return switch (refusal.get().scope()) {
                case CLIENT -> new Sent.Refused(kiosk ? "kiosk.error.busy" : "guest.error.too_many_requests",
                        waitText(refusal.get().waitSeconds()));
                case PARTY -> new Sent.Refused("guest.error.party_daily_limit");
            };
        }

        // The guest's earlier requests and 👍: the same song asked for again while it waits is not one more vote
        Set<Long> alreadyTheirs = new HashSet<>(guestSessionService.myRequestIds(session, partyCode));
        alreadyTheirs.addAll(guestVoteService.myVotes(session, partyCode));
        // the guest's dedication (V38) is checked by the AI in the same call; only the DJ sees it. Not while the DJ has them
        // switched off: one sent anyway (an old page) is left out, the song judged as without it
        String takenDedication = settings.isDedicationsOn() ? dedication : null;
        DjResponse response = songEvaluationService.evaluateAndSaveSong(partyCode, songName,
                styleOf(settings, locale), alreadyTheirs, takenDedication, kiosk);
        // A request that came to nothing — a mood sent back to the form, a rejected song, the guest's own song asked for
        // again — does not use the guest's limit up (the server's own limits keep counting it: against abuse)
        if (response.isMood() || response.ownSong() || DECISION_REJECTED.equalsIgnoreCase(response.decision())) {
            guestSessionService.giveBack(session, partyCode);
        }
        if (response.isMood()) {
            return new Sent.Mood(songName, takenDedication);
        }
        // Remembered in the session, so the party page can say that the guest's songs wait. The result itself is about this
        // request alone: another of the guest's waiting songs named there reads as the AI's mix-up.
        // A song that already had the guest's vote (their request or 👍) stays as it was: a 👍 stays one they can take back.
        if (!response.ownSong()) {
            guestSessionService.rememberRequest(session, partyCode, response.requestId());
        }
        return new Sent.Answered(response);
    }

    /**
     * The style the AI judges the request against — decided here, not by the form: the DJ's vibe when the DJ set one (by its name in
     * the guest's language), otherwise {@code ANY} (the AI judges by the DJ's vibe note, if any). The guests pick no vibe: the DJ
     * sets it.
     */
    String styleOf(PartySettingsEntity settings, Locale locale) {
        VibeType partyVibe = settings.getGlobalVibe();
        if (partyVibe != null && partyVibe != VibeType.ANY) {
            return messageSource.getMessage("vibe." + partyVibe.name(), null, partyVibe.name(), locale);
        }
        return VibeType.ANY.name();
    }

    /**
     * The guest's wait as people say it: from a minute on in whole minutes, rounded up ("3 min" — a phone at a party is not
     * read to the second), below a minute in seconds ("45 s"). The same short units in every language of the app.
     */
    static String waitText(long seconds) {
        return seconds < 60 ? seconds + " s" : (seconds + 59) / 60 + " min";
    }
}
