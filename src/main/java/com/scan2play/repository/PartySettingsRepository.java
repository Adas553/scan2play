package com.scan2play.repository;

import com.scan2play.entity.PartySettingsEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PartySettingsRepository extends JpaRepository<PartySettingsEntity, Long> {

    /**
     * Finds party settings by its unique 5-character code.
     *
     * @param partyCode The unique code of the party.
     * @return An Optional containing the PartySettingsEntity if found.
     */
    Optional<PartySettingsEntity> findByPartyCode(String partyCode);

    /** Whether the party is there (its unique index): a guest's request is saved only while it is ({@code SongRequestCommandService}). */
    boolean existsByPartyCode(String partyCode);

    /**
     * Finds party settings by the owner's unique ID (OAuth2 ID).
     *
     * @param ownerId The unique identifier of the DJ/Owner.
     * @return An Optional containing the PartySettingsEntity if found.
     */
    Optional<PartySettingsEntity> findByOwnerId(String ownerId);

    /**
     * The organisers new since {@code since} who have an address to write to (V48), the newest first, as many as {@code limit} — for
     * the site's owner on {@code /dj/stats}. The parties of before have no day and are never here; neither is the demo, which has no
     * address.
     */
    @Query("SELECT p FROM PartySettingsEntity p WHERE p.createdAt >= :since AND p.ownerEmail IS NOT NULL ORDER BY p.createdAt DESC")
    List<PartySettingsEntity> newOrganisers(@Param("since") Instant since, Limit limit);

    /** The party whose hosts' link (V29, {@code /h/{token}}) has this secret — the unique index of the column. */
    Optional<PartySettingsEntity> findByHostToken(String hostToken);

    /** The party whose staff invitation link (V30, {@code /join/{token}}) has this secret — the unique index of the column. */
    Optional<PartySettingsEntity> findByStaffToken(String staffToken);

    /**
     * The party, its row locked until the transaction ends ({@code SELECT … FOR UPDATE}): people joining its staff at the same moment
     * are counted one after another, so the staff never passes its limit ({@code PartyStaffService.join}, {@code StaffJoinIT}); two
     * writes of the settings take turns ({@code PartySettingsCommandService.updateSettings}); the account's deletion waits for them
     * ({@code AccountDeletionService}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PartySettingsEntity p WHERE p.partyCode = :partyCode")
    Optional<PartySettingsEntity> lockByPartyCode(@Param("partyCode") String partyCode);

    /**
     * The party whose invitation link this is, its row locked as {@link #lockByPartyCode} — the first read of a join, so the link's
     * role is read under the lock: an organiser making a new link at that moment is waited for, and PostgreSQL then checks the token
     * again — the old link joins nobody, not even with its old role ({@code PartyStaffRepositoryIT}).
     */
    /**
     * Opens or closes, in one statement, the parties whose planned moment (V55) has come by {@code now}, at most {@code limit} of them:
     * open by a due open, closed by a due close — both due (the server was down), as the later one left it —, and the due moments
     * cleared. Each row is found by the partial indexes of {@code next_open_at} / {@code next_close_at}; a row someone holds (a DJ's
     * write, another instance's run) is skipped until the next run, and after waiting for one PostgreSQL checks the outer condition
     * again, so a moment is never applied twice ({@code PartyScheduleIT}). Only through
     * {@code PartySettingsCommandService.applyDueSchedule}, which empties the cache.
     *
     * @return each party changed: its code and whether it is open now
     */
    @Query(value = "UPDATE party_settings p SET"
            + " active = CASE WHEN p.next_open_at <= :now AND p.next_close_at <= :now THEN p.next_open_at > p.next_close_at"
            + "               WHEN p.next_open_at <= :now THEN true ELSE false END,"
            + " next_open_at = CASE WHEN p.next_open_at <= :now THEN NULL ELSE p.next_open_at END,"
            + " next_close_at = CASE WHEN p.next_close_at <= :now THEN NULL ELSE p.next_close_at END"
            + " WHERE (p.next_open_at <= :now OR p.next_close_at <= :now)"
            + " AND p.id IN (SELECT d.id FROM party_settings d WHERE d.next_open_at <= :now OR d.next_close_at <= :now"
            + "              LIMIT :limit FOR UPDATE SKIP LOCKED)"
            + " RETURNING p.party_code, p.active", nativeQuery = true)
    List<Object[]> applyDueSchedule(@Param("now") Instant now, @Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PartySettingsEntity p WHERE p.staffToken = :staffToken")
    Optional<PartySettingsEntity> lockByStaffToken(@Param("staffToken") String staffToken);
}
