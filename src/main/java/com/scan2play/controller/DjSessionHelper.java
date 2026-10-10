package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PartyStaffService;
import com.scan2play.service.PartyStaffService.Access;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Which party a request works on, and what the person may do there (V30, V32).
 * <p>
 * <b>The page names the party.</b> Every form and fetch of the panel sends the party it shows ({@code partyCode}), and the server
 * checks the person's access to <i>that</i> party — so two tabs, or the installed app and a browser tab (on Android they share one
 * session), each work on the party they show. Until 2026-10-10 the party came from the session alone: a tab still showing another
 * party's queue cleared the person's own (the review). The session keeps only the <b>default panel</b> — the one
 * {@code /dj/dashboard} opens without {@code ?party=} — and a request without a code (a page loaded before this version) works on it.
 * <p>
 * Every request checks the access again (the owner without a query, a person of the staff by the unique index): an access taken away
 * or a permission changed counts at once. Two kinds of checks, against IDOR (a guessed 5-character code) and against a person of the
 * staff doing what they were not given: {@link #require} — the owner or a person with that {@link StaffPermission} — and
 * {@link #requireOwner} — the owner alone (the staff, the profiles and the tip link, clearing the history).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DjSessionHelper {

    static final String SESSION_PARTY_CODE = "djPartyCode";

    /** A note for the next panel shown (an access taken away, an invitation that did not work): {@link Note}. */
    static final String SESSION_PANEL_NOTE = "djPanelNote";

    /** How many times a new DJ's party is looked up or made before the error goes on ({@link #getOrCreateParty}). */
    static final int PARTY_CREATE_ATTEMPTS = 3;

    private final PartySettingsQueryService partySettingsQueryService;
    private final PartySettingsCommandService partySettingsCommandService;
    private final PartySettingsRepository partySettingsRepository;
    private final PartyStaffService partyStaffService;

    /**
     * A message for the panel: a key of the bundle, its argument (a party's name) and whether it is good news ("Dołączono…") or a
     * warning ("Organizator usunął Twój dostęp…").
     */
    public record Note(String key, String arg, boolean warning) implements Serializable {
    }

    /** Puts a note for the next panel shown (it survives a redirect: kept in the session until shown). */
    public void note(HttpSession session, Note note) {
        session.setAttribute(SESSION_PANEL_NOTE, note);
    }

    /** The note waiting for the panel, taken: shown once. */
    public Optional<Note> takeNote(HttpSession session) {
        Object note = session.getAttribute(SESSION_PANEL_NOTE);
        session.removeAttribute(SESSION_PANEL_NOTE);
        return Optional.ofNullable(note instanceof Note n ? n : null);
    }

    /**
     * The panel to show: the party asked for ({@code ?party=}) while the person may open it — it becomes the default panel —, else the
     * default panel while they may still open it, else their own party, else the first party they work at. A party they may no
     * longer open leaves a note ("Organizator … usunął Twój dostęp"). Empty when there is none of them and a note waits — a person who
     * came by an invitation, or whose access was taken away, gets no DJ's party made behind their back (the review, 2026-10-10: a
     * bartender found an empty DJ panel of a party made for them); a DJ's first login, with no note, makes the DJ's party.
     */
    public Optional<Access> panel(String requested, OAuth2AuthenticationToken authentication, HttpSession session) {
        String userId = authentication.getName();
        String asked = blankToNull(requested);
        String kept = (String) session.getAttribute(SESSION_PARTY_CODE);
        for (String code : new String[]{asked, Objects.equals(kept, asked) ? null : kept}) {
            if (code == null) {
                continue;
            }
            Optional<PartySettingsEntity> party = find(code);
            Optional<Access> access = party.flatMap(p -> partyStaffService.accessOf(p, userId));
            if (access.isPresent()) {
                session.setAttribute(SESSION_PARTY_CODE, code);
                return access;
            }
            boolean wasTheirs = code.equals(session.getAttribute(SESSION_PARTY_CODE));
            if (party.isPresent()) {
                log.info("{} has no access to party {} — another panel", userId, code);
                // named only when the person did work there (the session's panel): a code typed in the address names nothing
                note(session, wasTheirs ? new Note("dashboard.staff.removed", PartyStaffService.nameOf(party.get()), true)
                        : new Note("dashboard.staff.no_access", null, true));
            }
            if (wasTheirs) {
                session.removeAttribute(SESSION_PARTY_CODE);
            }
        }

        Optional<PartySettingsEntity> own = partySettingsRepository.findByOwnerId(userId);
        PartySettingsEntity party = own.orElse(null);
        if (party == null) {
            List<PartySettingsEntity> workedAt = partyStaffService.partiesOf(userId);
            if (!workedAt.isEmpty()) {
                party = workedAt.getFirst();
            } else if (session.getAttribute(SESSION_PANEL_NOTE) != null) {
                return Optional.empty();
            } else {
                party = getOrCreateParty(userId);
            }
        }
        session.setAttribute(SESSION_PARTY_CODE, party.getPartyCode());
        return partyStaffService.accessOf(party, userId);
    }

    /**
     * The default panel's party (what a page without a party code works on): {@link #panel} without asking for one; a person with
     * no panel at all gets a 403 here — the pages that may show "no panel" ask {@link #panel} themselves.
     */
    public PartySettingsEntity getPartySettings(OAuth2AuthenticationToken authentication, HttpSession session) {
        return panel(null, authentication, session).map(Access::party)
                .orElseThrow(() -> new AccessDeniedException("No panel"));
    }

    /**
     * The person's access to the party the page names — or, with no code (a page of an older version), to the default panel. A
     * party they neither own nor work at is a 403: nothing falls back to another party, so nothing is done to one the page does not
     * show.
     */
    public Access access(String partyCode, OAuth2AuthenticationToken authentication, HttpSession session) {
        if (blankToNull(partyCode) == null) {
            return panel(null, authentication, session).orElseThrow(() -> new AccessDeniedException("No panel"));
        }
        PartySettingsEntity party = find(partyCode).orElseThrow(() -> new AccessDeniedException("No such party: " + partyCode));
        return partyStaffService.accessOf(party, authentication.getName()).orElseThrow(() -> {
            log.warn("IDOR attempt (or an access taken away): {} at party {}", authentication.getName(), partyCode);
            return new AccessDeniedException("Not a party of this person: " + partyCode);
        });
    }

    /** The party, when the person owns it or was given {@code permission} there; else a 403. */
    public PartySettingsEntity require(String partyCode, StaffPermission permission, OAuth2AuthenticationToken authentication,
                                       HttpSession session) {
        Access access = access(partyCode, authentication, session);
        if (!access.may(permission)) {
            log.warn("{} (staff of party {}) tried {} without the permission", authentication.getName(),
                    access.party().getPartyCode(), permission);
            throw new AccessDeniedException("Not allowed: " + permission);
        }
        return access.party();
    }

    /** The party, when the person owns it; a person of the staff gets a 403 (what is never handed over). */
    public PartySettingsEntity requireOwner(String partyCode, OAuth2AuthenticationToken authentication, HttpSession session) {
        Access access = access(partyCode, authentication, session);
        if (!access.owner()) {
            log.warn("{} (staff of party {}) tried what only the owner may", authentication.getName(), access.party().getPartyCode());
            throw new AccessDeniedException("Only the party's owner may do this");
        }
        return access.party();
    }

    /**
     * The person left the party's staff ("Opuść obsługę"): it is no longer their default panel — else the next panel would say the
     * organiser took the access away.
     */
    public void forget(String partyCode, HttpSession session) {
        if (partyCode.equals(session.getAttribute(SESSION_PARTY_CODE))) {
            session.removeAttribute(SESSION_PARTY_CODE);
        }
    }

    /** Opens the person's own party ("Załóż własną imprezę" / their own panel): made now when they have none. */
    public PartySettingsEntity switchToOwnParty(OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity own = getOrCreateParty(authentication.getName());
        session.setAttribute(SESSION_PARTY_CODE, own.getPartyCode());
        return own;
    }

    /** Makes the party the default panel (a switch, a join); a 403 for a party the person may not open. */
    public PartySettingsEntity switchTo(String partyCode, OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity party = access(partyCode, authentication, session).party();
        session.setAttribute(SESSION_PARTY_CODE, party.getPartyCode());
        return party;
    }

    private Optional<PartySettingsEntity> find(String partyCode) {
        try {
            return Optional.of(partySettingsQueryService.getSettings(partyCode));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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

    /** The owner alone, at the party the page names (see {@link #requireOwner}). */
    public void validateOwnership(String partyCode, OAuth2AuthenticationToken authentication, HttpSession session) {
        requireOwner(partyCode, authentication, session);
    }

    /** The owner or anyone on the party's staff, at the party the page names (see {@link #access}). */
    public void validateAccess(String partyCode, OAuth2AuthenticationToken authentication, HttpSession session) {
        access(partyCode, authentication, session);
    }
}
