package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.PartyStaffEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.PartyStaffRepository;
import com.scan2play.util.Texts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A party's staff (V30, the owner 2026-10-09): a bartender or a second DJ joins the owner's party by the invitation link and works
 * its queue and history with their own Google account — no settings, no lists, no links, no clearing of the history. The owner
 * sees who has access and takes it away; a new link makes the old one dead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyStaffService {

    /** The most people on one party's staff. */
    public static final int MAX_STAFF = 10;

    private final PartyStaffRepository staffRepository;
    private final PartySettingsRepository partySettingsRepository;
    private final PartySettingsQueryService partySettingsQueryService;

    /** What came of an invitation link. */
    public enum JoinOutcome { JOINED, ALREADY, OWNER, FULL, UNKNOWN_LINK }

    /** @param party the party the link opens (null for {@link JoinOutcome#UNKNOWN_LINK} and {@link JoinOutcome#FULL}) */
    public record Joined(JoinOutcome outcome, PartySettingsEntity party) {
    }

    /** One panel the person may open: their own party, or one they work at. */
    public record Panel(String partyCode, String name, boolean own) {
    }

    /** Whether the person owns the party or is on its staff. The owner costs no query; a staff member one (the unique index). */
    public boolean hasAccess(PartySettingsEntity party, String userId) {
        return isOwner(party, userId) || staffRepository.existsByPartyCodeAndMemberId(party.getPartyCode(), userId);
    }

    public static boolean isOwner(PartySettingsEntity party, String userId) {
        return userId != null && userId.equals(party.getOwnerId());
    }

    /** The party an invitation link opens, if the link is the party's current one. */
    public Optional<PartySettingsEntity> partyOfLink(String token) {
        if (token == null || token.isEmpty() || token.length() > 32) {
            return Optional.empty();
        }
        return partySettingsRepository.findByStaffToken(token);
    }

    /**
     * The person opened the invitation link and logged in: they join the party's staff — unless they own it, are on it already, or
     * it has {@value #MAX_STAFF} people. Two tabs at once: the unique (party, member) keeps one row.
     */
    public Joined join(String token, String userId, String name) {
        Optional<PartySettingsEntity> found = partyOfLink(token);
        if (found.isEmpty()) {
            return new Joined(JoinOutcome.UNKNOWN_LINK, null);
        }
        PartySettingsEntity party = found.get();
        if (isOwner(party, userId)) {
            return new Joined(JoinOutcome.OWNER, party);
        }
        if (staffRepository.existsByPartyCodeAndMemberId(party.getPartyCode(), userId)) {
            return new Joined(JoinOutcome.ALREADY, party);
        }
        if (staffRepository.countByPartyCode(party.getPartyCode()) >= MAX_STAFF) {
            return new Joined(JoinOutcome.FULL, null);
        }
        String kept = Texts.oneLine(name, PartyStaffEntity.NAME_MAX);
        try {
            staffRepository.save(PartyStaffEntity.builder().partyCode(party.getPartyCode()).memberId(userId)
                    .memberName(kept.isEmpty() ? null : kept).joinedAt(Instant.now()).build());
        } catch (DataIntegrityViolationException e) {
            return new Joined(JoinOutcome.ALREADY, party);   // joined in another tab at the same moment
        }
        log.info("Party [{}]: {} joined the staff", party.getPartyCode(), userId);
        return new Joined(JoinOutcome.JOINED, party);
    }

    /** The party's staff, for the owner's list. */
    public List<PartyStaffEntity> staffOf(String partyCode) {
        return staffRepository.findTop20ByPartyCodeOrderByJoinedAtAscIdAsc(partyCode);
    }

    /** The staff's accounts: they get the notifications of new requests too. */
    public List<String> memberIds(String partyCode) {
        return staffOf(partyCode).stream().map(PartyStaffEntity::getMemberId).toList();
    }

    /** The owner takes one person's access away; false when the row is not of the owner's party. */
    public boolean remove(String partyCode, long staffId) {
        boolean removed = staffRepository.deleteFromParty(staffId, partyCode) > 0;
        if (removed) {
            log.info("Party [{}]: a staff member's access taken away", partyCode);
        }
        return removed;
    }

    /** The parties the person works at (not their own), as their settings — a party gone meanwhile is left out. */
    public List<PartySettingsEntity> partiesOf(String userId) {
        List<PartySettingsEntity> parties = new ArrayList<>();
        for (PartyStaffEntity row : staffRepository.findTop20ByMemberIdOrderByJoinedAtAscIdAsc(userId)) {
            try {
                parties.add(partySettingsQueryService.getSettings(row.getPartyCode()));
            } catch (IllegalArgumentException e) {
                // the party was deleted with its owner's account
            }
        }
        return parties;
    }

    /**
     * The panels the person may switch between: their own first (when they have one, or always for the owner of the current party),
     * then the parties they work at, named as the guests see who plays ("DJ Koko") or by the code.
     */
    public List<Panel> panelsOf(String userId, boolean hasOwnParty) {
        List<Panel> panels = new ArrayList<>();
        if (hasOwnParty) {
            panels.add(new Panel(null, null, true));
        }
        for (PartySettingsEntity party : partiesOf(userId)) {
            panels.add(new Panel(party.getPartyCode(), nameOf(party), false));
        }
        return panels;
    }

    /** How a party is named to its staff: who plays, else its code. */
    public static String nameOf(PartySettingsEntity party) {
        return party.getDjName() != null ? party.getDjName() : party.getPartyCode();
    }
}
