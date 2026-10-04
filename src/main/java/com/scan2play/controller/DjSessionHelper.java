package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartySettingsQueryService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Shared helper for resolving the current DJ's party settings from the HTTP session.
 * Caches the partyCode in the session to avoid redundant DB lookups on every request.
 * <p>
 * Also provides ownership validation to prevent IDOR attacks — all DJ endpoints
 * that accept a {@code partyCode} parameter should call {@link #validateOwnership}
 * before performing any mutation.
 * <p>
 * Used by all DJ-facing controllers that need access to the current party context.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DjSessionHelper {

    static final String SESSION_PARTY_CODE = "djPartyCode";
    /** The kind of party the DJ chose on the landing page before Google's login (HomeController.start); used once. */
    static final String SESSION_CHOSEN_PROVIDER = "djChosenProvider";

    private final PartySettingsQueryService partySettingsQueryService;
    private final PartySettingsCommandService partySettingsCommandService;

    /**
     * Resolves party settings using a cached partyCode from the HTTP session when available.
     * Falls back to {@code getOrCreatePartyForDj} (DB lookup by ownerId) on first access,
     * then stores the partyCode in the session for subsequent requests.
     *
     * @param authentication The OAuth2 authentication token.
     * @param session        The current HTTP session.
     * @return The PartySettingsEntity for this DJ.
     */
    public PartySettingsEntity getPartySettings(OAuth2AuthenticationToken authentication, HttpSession session) {
        MusicProviderType chosen = takeChosenProvider(session);
        String cachedPartyCode = (String) session.getAttribute(SESSION_PARTY_CODE);

        if (cachedPartyCode != null) {
            try {
                return giveChosenKind(partySettingsQueryService.getSettings(cachedPartyCode), chosen);
            } catch (IllegalArgumentException e) {
                log.warn("Cached partyCode '{}' no longer valid, falling back to ownerId lookup", cachedPartyCode);
                session.removeAttribute(SESSION_PARTY_CODE);
            }
        }

        String ownerId = authentication.getName();
        MusicProviderType provider = chosen != null ? chosen : MusicProviderType.YOUTUBE;
        PartySettingsEntity settings = giveChosenKind(partySettingsCommandService.getOrCreatePartyForDj(ownerId, provider), chosen);
        session.setAttribute(SESSION_PARTY_CODE, settings.getPartyCode());
        return settings;
    }

    /**
     * The kind of party chosen on the landing page before this login (HomeController.start), taken out of the session: it counts
     * once.
     */
    private MusicProviderType takeChosenProvider(HttpSession session) {
        Object chosen = session.getAttribute(SESSION_CHOSEN_PROVIDER);
        if (chosen == null) {
            return null;
        }
        session.removeAttribute(SESSION_CHOSEN_PROVIDER);
        try {
            return MusicProviderType.valueOf(chosen.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The DJ's party, of the kind they chose: one DJ has one party, so a DJ with a YouTube party who picks the requests-only tile
     * (or back) gets the same party — the same code, the same QR — of the other kind. A requests-only party has no player, so
     * its Auto-Pilot is off.
     */
    private PartySettingsEntity giveChosenKind(PartySettingsEntity settings, MusicProviderType chosen) {
        if (chosen == null || settings.getActiveProvider() == chosen) {
            return settings;
        }
        log.info("Party [{}]: {} -> {} (the DJ's choice on the landing page)", settings.getPartyCode(), settings.getActiveProvider(), chosen);
        return partySettingsCommandService.updateSettings(settings.getPartyCode(), party -> {
            party.setActiveProvider(chosen);
            if (chosen == MusicProviderType.REQUESTS_ONLY) {
                party.setPlaybackMode(PlaybackMode.MANUAL);
            }
        });
    }

    /**
     * Validates that the given {@code partyCode} belongs to the currently authenticated DJ.
     * Compares against the session-cached partyCode (set during {@link #getPartySettings}).
     * <p>
     * <b>Must be called in every DJ endpoint that accepts {@code partyCode} as a request parameter</b>
     * to prevent IDOR attacks (an attacker guessing/brute-forcing a 5-char code).
     *
     * @param partyCode      The partyCode from the incoming request.
     * @param authentication The current DJ's OAuth2 token.
     * @param session        The current HTTP session.
     * @throws org.springframework.security.access.AccessDeniedException if the partyCode does not belong to this DJ.
     */
    public void validateOwnership(String partyCode, OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity settings = getPartySettings(authentication, session);
        if (!settings.getPartyCode().equals(partyCode)) {
            log.warn("IDOR attempt: DJ {} tried to access party {} (owns {})",
                    authentication.getName(), partyCode, settings.getPartyCode());
            throw new org.springframework.security.access.AccessDeniedException(
                    "You do not own party: " + partyCode);
        }
    }
}

