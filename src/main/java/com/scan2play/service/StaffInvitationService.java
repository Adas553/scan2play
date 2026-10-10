package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.entity.StaffInvitationEntity;
import com.scan2play.model.StaffPermission;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.StaffInvitationRepository;
import com.scan2play.service.PartyStaffService.JoinOutcome;
import com.scan2play.util.EmailAddresses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Invitations to a party's staff by the e-mail address of a Google account (V34, the owner 2026-10-10). The organiser types the
 * address and the role; nothing is sent — whoever logs in with a Google account of that (verified) address is asked "Dołącz" /
 * "Nie, dziękuję" when the panel opens. The invitation link stays as the other way. An invitation waits
 * {@value StaffInvitationEntity#MAX_AGE_DAYS} days; the staff and the invitations waiting take at most
 * {@value PartyStaffService#MAX_STAFF} places together, counted under the party row's lock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffInvitationService {

    private final StaffInvitationRepository invitationRepository;
    private final PartySettingsRepository partySettingsRepository;
    private final PartyStaffService partyStaffService;
    private final PartySettingsQueryService partySettingsQueryService;

    /** What came of the organiser's "Zaproś". */
    public enum InviteOutcome { INVITED, RENEWED, INVALID_ADDRESS, FULL }

    /** What came of the person's "Dołącz": the outcome of joining, or {@code GONE} — cancelled, answered elsewhere, too old. */
    public enum AnswerOutcome { JOINED, ALREADY, OWNER, FULL, GONE }

    /** @param party the party joined (null for {@link AnswerOutcome#GONE}) */
    public record Answer(AnswerOutcome outcome, PartySettingsEntity party) {
    }

    /** An invitation waiting for the person who logged in, with the party it names (for the question). */
    public record Waiting(StaffInvitationEntity invitation, PartySettingsEntity party) {
    }

    /** The oldest moment of an invitation still waiting. */
    static Instant waitingSince() {
        return Instant.now().minus(StaffInvitationEntity.MAX_AGE_DAYS, ChronoUnit.DAYS);
    }

    /**
     * The organiser invites an address with these permissions. The same address again (waiting, or past its time and not yet
     * cleaned up) gets the new permissions and its 30 days again — one row per address and party. Under the party row's lock: a new
     * invitation never passes the places left, however many are made, or people join by the link, at once.
     */
    @Transactional
    public InviteOutcome invite(String partyCode, String typedAddress, Set<StaffPermission> permissions) {
        Optional<String> address = EmailAddresses.clean(typedAddress);
        if (address.isEmpty()) {
            return InviteOutcome.INVALID_ADDRESS;
        }
        String key = EmailAddresses.key(address.get());
        Set<StaffPermission> granted = permissions.isEmpty() ? EnumSet.noneOf(StaffPermission.class) : EnumSet.copyOf(permissions);
        partySettingsRepository.lockByPartyCode(partyCode);
        Optional<StaffInvitationEntity> earlier = invitationRepository.findByPartyCodeAndEmailKey(partyCode, key);
        boolean waiting = earlier.isPresent() && earlier.get().getInvitedAt().isAfter(waitingSince());
        if (!waiting && partyStaffService.placesTaken(partyCode) >= PartyStaffService.MAX_STAFF) {
            return InviteOutcome.FULL;
        }
        StaffInvitationEntity invitation = earlier.orElseGet(() -> StaffInvitationEntity.builder().partyCode(partyCode).emailKey(key).build());
        invitation.setEmail(address.get());
        invitation.setPermissions(granted);
        invitation.setInvitedAt(Instant.now());
        invitationRepository.save(invitation);
        log.info("Party [{}]: an invitation by e-mail {} ({})", partyCode, earlier.isPresent() ? "renewed" : "made",
                StaffPermission.format(granted));
        return earlier.isPresent() ? InviteOutcome.RENEWED : InviteOutcome.INVITED;
    }

    /** The party's invitations waiting, for the organiser's list. */
    public List<StaffInvitationEntity> waitingAt(String partyCode) {
        return invitationRepository.findTop20ByPartyCodeAndInvitedAtAfterOrderByInvitedAtAscIdAsc(partyCode, waitingSince());
    }

    /** The organiser cancels an invitation; false when it is not one of their party (the id comes from the form). */
    public boolean cancel(String partyCode, long invitationId) {
        boolean cancelled = invitationRepository.deleteFromParty(invitationId, partyCode) > 0;
        if (cancelled) {
            log.info("Party [{}]: an invitation by e-mail cancelled", partyCode);
        }
        return cancelled;
    }

    /**
     * The first invitation waiting for the person's verified address, with its party — the panel asks it before anything else. Empty
     * without a verified address (Google says whether it is), or with nothing waiting: one query by the index on {@code email_key}.
     */
    public Optional<Waiting> waitingFor(String verifiedAddress) {
        if (verifiedAddress == null || verifiedAddress.isBlank()) {
            return Optional.empty();
        }
        for (StaffInvitationEntity invitation : invitationRepository.findTop10ByEmailKeyAndInvitedAtAfterOrderByInvitedAtAscIdAsc(
                EmailAddresses.key(verifiedAddress), waitingSince())) {
            try {
                return Optional.of(new Waiting(invitation, partySettingsQueryService.getSettings(invitation.getPartyCode())));
            } catch (IllegalArgumentException e) {
                // the party was deleted meanwhile (its invitations go with it)
            }
        }
        return Optional.empty();
    }

    /**
     * "Dołącz": the person whose verified address the invitation names joins the party's staff with its permissions, and the
     * invitation goes — in one transaction, under the party row's lock: the same invitation answered twice at once (two tabs, the
     * app and a browser) joins once ({@code StaffInvitationIT}). {@code GONE} when it is not there any more, is not for this address,
     * or waited too long. The person's own party (the organiser invited themselves): the invitation goes, nothing joins.
     */
    @Transactional
    public Answer accept(long invitationId, String verifiedAddress, String userId, String name) {
        Optional<StaffInvitationEntity> found = mine(invitationId, verifiedAddress);
        if (found.isEmpty()) {
            return new Answer(AnswerOutcome.GONE, null);
        }
        String key = found.get().getEmailKey();
        Optional<PartySettingsEntity> party = partySettingsRepository.lockByPartyCode(found.get().getPartyCode());
        // asked the database again under the lock (a query, not the entity in memory): another tab may have answered it meanwhile
        if (party.isEmpty() || !invitationRepository.existsByIdAndEmailKeyAndInvitedAtAfter(invitationId, key, waitingSince())) {
            return new Answer(AnswerOutcome.GONE, null);
        }
        JoinOutcome joined = partyStaffService.addToStaff(party.get(), userId, name, found.get().getPermissions(), true);
        if (joined != JoinOutcome.FULL) {
            invitationRepository.deleteForAddress(invitationId, key);
        }
        return new Answer(switch (joined) {
            case JOINED -> AnswerOutcome.JOINED;
            case ALREADY -> AnswerOutcome.ALREADY;
            case OWNER -> AnswerOutcome.OWNER;
            case FULL, UNKNOWN_LINK -> AnswerOutcome.FULL;
        }, party.get());
    }

    /** "Nie, dziękuję": the invitation goes (the organiser's list no longer shows it); false when it is not there for this address. */
    public boolean decline(long invitationId, String verifiedAddress) {
        if (verifiedAddress == null || verifiedAddress.isBlank()) {
            return false;
        }
        boolean declined = invitationRepository.deleteForAddress(invitationId, EmailAddresses.key(verifiedAddress)) > 0;
        if (declined) {
            log.info("An invitation by e-mail declined");
        }
        return declined;
    }

    /** The invitation, when it waits for this address. */
    private Optional<StaffInvitationEntity> mine(long invitationId, String verifiedAddress) {
        if (verifiedAddress == null || verifiedAddress.isBlank()) {
            return Optional.empty();
        }
        String key = EmailAddresses.key(verifiedAddress);
        return invitationRepository.findById(invitationId)
                .filter(i -> i.getEmailKey().equals(key) && i.getInvitedAt().isAfter(waitingSince()));
    }

    /** Runs daily at 04:50: the invitations no one answered in {@value StaffInvitationEntity#MAX_AGE_DAYS} days go. */
    @Scheduled(cron = "0 50 4 * * *")
    public void purgeOldInvitations() {
        int deleted = invitationRepository.deleteMadeBefore(waitingSince());
        if (deleted > 0) {
            log.info("Staff invitation cleanup: deleted {} invitation(s) older than {} days", deleted, StaffInvitationEntity.MAX_AGE_DAYS);
        }
    }
}
