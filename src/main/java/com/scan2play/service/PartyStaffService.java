package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.PartyStaffEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.PartyStaffRepository;
import com.scan2play.repository.StaffInvitationRepository;
import com.scan2play.util.Texts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A party's staff (V30, the owner 2026-10-09): a bartender, a second DJ, the venue's manager joins the owner's party by the invitation
 * link and works it with their own Google account — as far as the owner allows (V32: a role or permissions ticked one by one,
 * {@link StaffPermission}). The owner sees who has access, changes what each may do and takes the access away; a new link makes
 * the old one dead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyStaffService {

    /** The most people on one party's staff. */
    public static final int MAX_STAFF = 10;

    private final PartyStaffRepository staffRepository;
    private final StaffInvitationRepository invitationRepository;
    private final PartySettingsRepository partySettingsRepository;
    private final PartySettingsQueryService partySettingsQueryService;

    /** What came of an invitation link. */
    public enum JoinOutcome { JOINED, ALREADY, OWNER, FULL, UNKNOWN_LINK }

    /** @param party the party the link opens (null for {@link JoinOutcome#UNKNOWN_LINK}) */
    public record Joined(JoinOutcome outcome, PartySettingsEntity party) {
    }

    /** One panel the person may open: their own party (null code: not made yet), or one they work at — named as its staff see it. */
    public record Panel(String partyCode, String name, boolean own) {
    }

    /**
     * What a person may do at a party: the owner everything (and what is never handed over), a person of the staff what the owner
     * gave them.
     */
    public record Access(PartySettingsEntity party, boolean owner, Set<StaffPermission> permissions) {

        public boolean may(StaffPermission permission) {
            return owner || permissions.contains(permission);
        }

        /** {@link #may(StaffPermission)} by the permission's name — for the templates: {@code access.may('QUEUE')}. */
        public boolean may(String permission) {
            return may(StaffPermission.valueOf(permission));
        }

        /** Whether any of the party's settings cards is the person's (the owner's all, a co-organiser's some). */
        public boolean maySomeSettings() {
            return may(StaffPermission.VIBE) || may(StaffPermission.LIMITS) || may(StaffPermission.HOST_LISTS);
        }

        /** The role the permissions make ({@link StaffRole#CUSTOM} when ticked one by one); null for the owner. */
        public StaffRole role() {
            return owner ? null : StaffRole.of(permissions);
        }
    }

    /**
     * What the person may do at the party, or empty when they neither own it nor are on its staff. The owner costs no query; a person
     * of the staff one (the unique index) — on every request, so an access taken away or a permission changed counts at once.
     */
    public Optional<Access> accessOf(PartySettingsEntity party, String userId) {
        if (isOwner(party, userId)) {
            return Optional.of(new Access(party, true, StaffPermission.all()));
        }
        return staffRepository.findByPartyCodeAndMemberId(party.getPartyCode(), userId)
                .map(row -> new Access(party, false, EnumSet.copyOf(withNone(row.getPermissions()))));
    }

    private static Set<StaffPermission> withNone(Set<StaffPermission> permissions) {
        return permissions == null || permissions.isEmpty() ? EnumSet.noneOf(StaffPermission.class) : permissions;
    }

    /** Whether the person owns the party or is on its staff. */
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
     * The person said "Dołącz" on the invitation link: they join the party's staff with what the link gives (its role, V33) — unless
     * they own it, are on it already, or its places are taken ({@link #addToStaff}). The party is read with its row locked
     * ({@code SELECT … FOR UPDATE} by the token): people joining at the same moment are counted one after another, so the staff never
     * passes its limit and a person joining in two tabs at once makes one row; and a new link made at that moment is waited for — the
     * old one then joins nobody ({@code PartyStaffRepositoryIT}).
     */
    @Transactional
    public Joined join(String token, String userId, String name) {
        if (token == null || token.isEmpty() || token.length() > 32) {
            return new Joined(JoinOutcome.UNKNOWN_LINK, null);
        }
        Optional<PartySettingsEntity> found = partySettingsRepository.lockByStaffToken(token);
        if (found.isEmpty()) {
            return new Joined(JoinOutcome.UNKNOWN_LINK, null);
        }
        PartySettingsEntity party = found.get();
        return new Joined(addToStaff(party, userId, name, party.staffLinkGrants(), false), party);
    }

    /**
     * Adds the person to the party's staff with these permissions — the caller holds the party row's lock, in its transaction. Not the
     * owner, not twice; and the staff with the invitations waiting by e-mail (V34) take at most {@value #MAX_STAFF} places — an
     * invitation being answered ({@code answeringAnInvitation}) is one of them already.
     */
    JoinOutcome addToStaff(PartySettingsEntity party, String userId, String name, Set<StaffPermission> permissions,
                           boolean answeringAnInvitation) {
        if (isOwner(party, userId)) {
            return JoinOutcome.OWNER;
        }
        if (staffRepository.existsByPartyCodeAndMemberId(party.getPartyCode(), userId)) {
            return JoinOutcome.ALREADY;
        }
        if (placesTaken(party.getPartyCode()) - (answeringAnInvitation ? 1 : 0) >= MAX_STAFF) {
            return JoinOutcome.FULL;
        }
        String kept = Texts.oneLine(name, PartyStaffEntity.NAME_MAX);
        staffRepository.save(PartyStaffEntity.builder().partyCode(party.getPartyCode()).memberId(userId)
                .memberName(kept.isEmpty() ? null : kept).joinedAt(Instant.now())
                .permissions(permissions.isEmpty() ? EnumSet.noneOf(StaffPermission.class) : EnumSet.copyOf(permissions)).build());
        log.info("Party [{}]: {} joined the staff as {}", party.getPartyCode(), userId, StaffPermission.format(permissions));
        return JoinOutcome.JOINED;
    }

    /** The party's places taken: its staff and the invitations by e-mail still waiting (V34) — together at most {@value #MAX_STAFF}. */
    public long placesTaken(String partyCode) {
        return staffRepository.countByPartyCode(partyCode)
                + invitationRepository.countByPartyCodeAndInvitedAtAfter(partyCode, StaffInvitationService.waitingSince());
    }

    /** The party's staff, for the owner's list. */
    public List<PartyStaffEntity> staffOf(String partyCode) {
        return staffRepository.findTop20ByPartyCodeOrderByJoinedAtAscIdAsc(partyCode);
    }

    /** The staff's accounts: they get the notifications of new requests too. */
    public List<String> memberIds(String partyCode) {
        return staffOf(partyCode).stream().map(PartyStaffEntity::getMemberId).toList();
    }

    /**
     * The owner changes what one person may do: a role's set or ticked one by one. False when the row is not of the owner's party
     * (the id comes from the form). The person's next request already counts with it.
     */
    @Transactional
    public boolean setPermissions(String partyCode, long staffId, Set<StaffPermission> permissions) {
        Optional<PartyStaffEntity> row = staffRepository.findById(staffId).filter(s -> s.getPartyCode().equals(partyCode));
        row.ifPresent(s -> {
            s.setPermissions(permissions.isEmpty() ? EnumSet.noneOf(StaffPermission.class) : EnumSet.copyOf(permissions));
            log.info("Party [{}]: a staff member's permissions now {}", partyCode, StaffPermission.format(permissions));
        });
        return row.isPresent();
    }

    /** The owner takes one person's access away; false when the row is not of the owner's party. */
    public boolean remove(String partyCode, long staffId) {
        boolean removed = staffRepository.deleteFromParty(staffId, partyCode) > 0;
        if (removed) {
            log.info("Party [{}]: a staff member's access taken away", partyCode);
        }
        return removed;
    }

    /** The person leaves the party's staff ("Opuść obsługę"); false when they were not on it. */
    public boolean leave(String partyCode, String userId) {
        boolean left = staffRepository.deleteMember(partyCode, userId) > 0;
        if (left) {
            log.info("Party [{}]: {} left the staff", partyCode, userId);
        }
        return left;
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
     * The panels the person may switch between: their own first, when they have one — by its name, as the guests see who plays — then
     * the parties they work at. A person without a party of their own (a bartender) makes one in the menu "Konto" ("Załóż własną
     * imprezę"), not here: "Mój panel" read as theirs and made a DJ's party of a click (the review, 2026-10-10).
     */
    public List<Panel> panelsOf(String userId) {
        List<Panel> panels = new ArrayList<>();
        partySettingsRepository.findByOwnerId(userId).ifPresent(own -> panels.add(new Panel(own.getPartyCode(), nameOf(own), true)));
        for (PartySettingsEntity party : partiesOf(userId)) {
            panels.add(new Panel(party.getPartyCode(), nameOf(party), false));
        }
        return panels;
    }

    /** Whether the person has a party of their own (else the menu offers to make one). */
    public boolean hasOwnParty(String userId) {
        return partySettingsRepository.findByOwnerId(userId).isPresent();
    }

    /** How a party is named to its staff and in the panel switcher: who plays, else the organiser's name, else its code. */
    public static String nameOf(PartySettingsEntity party) {
        if (party.getDjName() != null) {
            return party.getDjName();
        }
        return party.getOwnerName() != null ? party.getOwnerName() : party.getPartyCode();
    }
}
