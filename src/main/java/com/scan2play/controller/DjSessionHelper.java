package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PartyStaffService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The party the logged-in person works on now — the panel's party — kept in the HTTP session: their own, or one whose staff they
 * are (V30). Every request checks that the person still may open it (the owner without a query; a staff member by the unique
 * index — an access the owner took away ends with the next request), and falls back to the person's own party otherwise.
 * <p>
 * Two kinds of checks for the DJ's endpoints, against IDOR (a guessed 5-character code) and against a staff member doing what only
 * the owner may: {@link #validateAccess} — the owner or the staff (the queue, the history, the party open or closed) — and
 * {@link #validateOwnership} / {@link #getOwnedPartySettings} — the owner alone (the settings, the lists and links, the staff, the
 * QR print, the evening summary, clearing the history).
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
    private final PartySettingsRepository partySettingsRepository;
    private final PartyStaffService partyStaffService;

    /**
     * The panel's party: the one in the session while the person may still open it, else their own party — or, for a person
     * without one who works at a party (a bartender), that party: a bartender gets no party of their own until they open "Mój
     * panel". The first login of a DJ makes their party.
     */
    public PartySettingsEntity getPartySettings(OAuth2AuthenticationToken authentication, HttpSession session) {
        String userId = authentication.getName();
        String cachedPartyCode = (String) session.getAttribute(SESSION_PARTY_CODE);

        if (cachedPartyCode != null) {
            try {
                PartySettingsEntity settings = partySettingsQueryService.getSettings(cachedPartyCode);
                if (partyStaffService.hasAccess(settings, userId)) {
                    return settings;
                }
                log.info("{} no longer has access to party {} — back to their own panel", userId, cachedPartyCode);
            } catch (IllegalArgumentException e) {
                log.warn("Cached partyCode '{}' no longer valid, falling back to ownerId lookup", cachedPartyCode);
            }
            session.removeAttribute(SESSION_PARTY_CODE);
        }

        PartySettingsEntity settings = defaultParty(userId);
        session.setAttribute(SESSION_PARTY_CODE, settings.getPartyCode());
        return settings;
    }

    private PartySettingsEntity defaultParty(String userId) {
        Optional<PartySettingsEntity> own = partySettingsRepository.findByOwnerId(userId);
        if (own.isPresent()) {
            return own.get();
        }
        List<PartySettingsEntity> workedAt = partyStaffService.partiesOf(userId);
        return workedAt.isEmpty() ? getOrCreateParty(userId) : workedAt.getFirst();
    }

    /** Whether the person owns the panel's party (else they are on its staff). */
    public boolean isOwner(OAuth2AuthenticationToken authentication, HttpSession session) {
        return PartyStaffService.isOwner(getPartySettings(authentication, session), authentication.getName());
    }

    /** The panel's party when the person owns it; a staff member gets a 403 (what only the owner may do). */
    public PartySettingsEntity getOwnedPartySettings(OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity settings = getPartySettings(authentication, session);
        if (!PartyStaffService.isOwner(settings, authentication.getName())) {
            log.warn("{} (staff of party {}) tried what only the owner may", authentication.getName(), settings.getPartyCode());
            throw new AccessDeniedException("Only the party's owner may do this");
        }
        return settings;
    }

    /** Opens the person's own party in the panel ("Mój panel"): made now when they have none (a bartender who starts DJ-ing). */
    public PartySettingsEntity switchToOwnParty(OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity own = getOrCreateParty(authentication.getName());
        session.setAttribute(SESSION_PARTY_CODE, own.getPartyCode());
        return own;
    }

    /** Opens a party the person works at (or owns) in the panel; a 403 for any other. */
    public PartySettingsEntity switchTo(String partyCode, OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity settings;
        try {
            settings = partySettingsQueryService.getSettings(partyCode);
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("No such party: " + partyCode);
        }
        if (!partyStaffService.hasAccess(settings, authentication.getName())) {
            log.warn("IDOR attempt: {} tried to open the panel of party {}", authentication.getName(), partyCode);
            throw new AccessDeniedException("Not your party: " + partyCode);
        }
        session.setAttribute(SESSION_PARTY_CODE, partyCode);
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
     * Validates that {@code partyCode} is the panel's party and that the person <b>owns</b> it: the settings and everything else
     * only the owner may change. <b>Must be called in every such endpoint that accepts {@code partyCode}</b> (IDOR: a
     * guessed or brute-forced 5-character code).
     *
     * @throws AccessDeniedException if the party is not the panel's, or the person is only on its staff
     */
    public void validateOwnership(String partyCode, OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity settings = getOwnedPartySettings(authentication, session);
        if (!settings.getPartyCode().equals(partyCode)) {
            log.warn("IDOR attempt: DJ {} tried to access party {} (owns {})",
                    authentication.getName(), partyCode, settings.getPartyCode());
            throw new AccessDeniedException("You do not own party: " + partyCode);
        }
    }

    /**
     * Validates that {@code partyCode} is the panel's party — the person owns it or is on its staff: the queue, the history and the
     * feedback.
     *
     * @throws AccessDeniedException if the party is not the panel's
     */
    public void validateAccess(String partyCode, OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity settings = getPartySettings(authentication, session);
        if (!settings.getPartyCode().equals(partyCode)) {
            log.warn("IDOR attempt: {} tried to access party {} (works on {})",
                    authentication.getName(), partyCode, settings.getPartyCode());
            throw new AccessDeniedException("Not the panel's party: " + partyCode);
        }
    }
}
