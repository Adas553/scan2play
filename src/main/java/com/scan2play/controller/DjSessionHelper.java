package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartySettingsQueryService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
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

    /** How many times a new DJ's party is looked up or made before the error goes on ({@link #getOrCreateParty}). */
    static final int PARTY_CREATE_ATTEMPTS = 3;

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
        String cachedPartyCode = (String) session.getAttribute(SESSION_PARTY_CODE);

        if (cachedPartyCode != null) {
            try {
                return partySettingsQueryService.getSettings(cachedPartyCode);
            } catch (IllegalArgumentException e) {
                log.warn("Cached partyCode '{}' no longer valid, falling back to ownerId lookup", cachedPartyCode);
                session.removeAttribute(SESSION_PARTY_CODE);
            }
        }

        PartySettingsEntity settings = getOrCreateParty(authentication.getName());
        session.setAttribute(SESSION_PARTY_CODE, settings.getPartyCode());
        return settings;
    }

    /**
     * The DJ's party, made on the first login. A first login opened in several tabs at once makes the party in each of them and
     * all but one hit the UNIQUE owner_id ({@code FirstLoginIT}); a new random code may also, very rarely, be taken already. Each
     * try is a transaction of its own, so the next one simply finds the party the other tab made (or draws another code).
     */
    private PartySettingsEntity getOrCreateParty(String ownerId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return partySettingsCommandService.getOrCreatePartyForDj(ownerId);
            } catch (DataIntegrityViolationException e) {
                if (attempt >= PARTY_CREATE_ATTEMPTS) {
                    throw e;
                }
                log.info("DJ {}: the party was made at the same moment elsewhere (or its code was taken) — looking again", ownerId);
            }
        }
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
     * @throws AccessDeniedException if the partyCode does not belong to this DJ.
     */
    public void validateOwnership(String partyCode, OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity settings = getPartySettings(authentication, session);
        if (!settings.getPartyCode().equals(partyCode)) {
            log.warn("IDOR attempt: DJ {} tried to access party {} (owns {})",
                    authentication.getName(), partyCode, settings.getPartyCode());
            throw new AccessDeniedException("You do not own party: " + partyCode);
        }
    }
}

