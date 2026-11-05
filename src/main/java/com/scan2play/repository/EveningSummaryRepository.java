package com.scan2play.repository;

import com.scan2play.entity.EveningSummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * "Podsumowanie wieczoru" beyond the guests' requests (V61): the evenings the DJ named or shared ({@code evening_summary}) and the
 * songs of the evenings whose requests are gone ({@code evening_song} — copied by {@code SongRequestRepository}'s deletions).
 */
public interface EveningSummaryRepository extends JpaRepository<EveningSummaryEntity, Long> {

    Optional<EveningSummaryEntity> findByPartyCodeAndEvening(String partyCode, LocalDate evening);

    /** The evening of a hosts' link; the caller checks it still works. {@code uk_evening_summary_share_token} finds it. */
    Optional<EveningSummaryEntity> findByShareToken(String shareToken);

    /** The DJ's title of an evening (null clears it): one upsert — two saves at once end as one of them, never a duplicate key. */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO evening_summary (party_code, evening, title, updated_at) VALUES (:partyCode, :evening, :title, :now)"
            + " ON CONFLICT (party_code, evening) DO UPDATE SET title = EXCLUDED.title, updated_at = EXCLUDED.updated_at",
            nativeQuery = true)
    int saveTitle(@Param("partyCode") String partyCode, @Param("evening") LocalDate evening, @Param("title") String title,
                  @Param("now") Instant now);

    /** A new hosts' link for an evening (an old one stops working) until {@code expiresAt}; null token: the link switched off. */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO evening_summary (party_code, evening, share_token, share_expires_at, updated_at)"
            + " VALUES (:partyCode, :evening, :token, :expiresAt, :now) ON CONFLICT (party_code, evening) DO UPDATE"
            + " SET share_token = EXCLUDED.share_token, share_expires_at = EXCLUDED.share_expires_at, updated_at = EXCLUDED.updated_at",
            nativeQuery = true)
    int saveShare(@Param("partyCode") String partyCode, @Param("evening") LocalDate evening, @Param("token") String token,
                  @Param("expiresAt") Instant expiresAt, @Param("now") Instant now);

    /**
     * The songs of one evening whose requests are gone (the 30 days' purge, "Wyczyść historię"), the first asked first, at most
     * {@code limit}. A row: id, song name (null for a request the AI rejected), requested at, played at, status, votes, tips,
     * dedications, added by the DJ, from the kiosk, why the AI rejected it. {@code idx_evening_song_party_time} finds them.
     */
    @Query(value = "SELECT song_request_id, song_name, requested_at, played_at, status, votes, tips, dedications, added_by_dj, via_kiosk,"
            + " reject_reason"
            + " FROM evening_song WHERE party_code = :partyCode AND requested_at >= :from AND requested_at < :to"
            + " ORDER BY requested_at, song_request_id LIMIT :limit", nativeQuery = true)
    List<Object[]> findSongsBetween(@Param("partyCode") String partyCode, @Param("from") Instant from, @Param("to") Instant to,
                                    @Param("limit") int limit);

    /**
     * Whether the party has a request between {@code from} and {@code to} — one still there or one kept of a deleted one (an evening
     * there is: it may be named and shared). Stops at the first row; {@code idx_party_decision_time} and
     * {@code idx_evening_song_party_time} find it.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM song_requests WHERE party_code = :partyCode AND requested_at >= :from"
            + " AND requested_at < :to) OR EXISTS (SELECT 1 FROM evening_song WHERE party_code = :partyCode AND requested_at >= :from"
            + " AND requested_at < :to)", nativeQuery = true)
    boolean hasRequestsBetween(@Param("partyCode") String partyCode, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * The purge of the evenings' songs older than {@code cutoff}, at most {@code batchSize} rows a call (each its own transaction,
     * as the requests' purge); {@code idx_evening_song_time} finds them.
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM evening_song WHERE song_request_id IN "
            + "(SELECT song_request_id FROM evening_song WHERE requested_at < :cutoff LIMIT :batchSize)", nativeQuery = true)
    int deleteSongsRequestedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /** The evenings' titles and links of before {@code before}: their songs are gone too. */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM evening_summary WHERE evening < :before", nativeQuery = true)
    int deleteEveningsBefore(@Param("before") LocalDate before);

    /**
     * One more guest's request refused before the AI judged a song (V63), counted for the party's evening and the reason: one upsert,
     * two at once both counted (the row's lock).
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO evening_refusal (party_code, evening, reason, count) VALUES (:partyCode, :evening, :reason, 1)"
            + " ON CONFLICT (party_code, evening, reason) DO UPDATE SET count = evening_refusal.count + 1", nativeQuery = true)
    int countRefusal(@Param("partyCode") String partyCode, @Param("evening") LocalDate evening, @Param("reason") String reason);

    /** The evening's refusals before the AI: a row {@code [reason, count]}, at most one a reason (the primary key finds them). */
    @Query(value = "SELECT reason, count FROM evening_refusal WHERE party_code = :partyCode AND evening = :evening", nativeQuery = true)
    List<Object[]> findRefusals(@Param("partyCode") String partyCode, @Param("evening") LocalDate evening);

    /** The refusals' counts of the evenings before {@code before} ({@code idx_evening_refusal_evening}). */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM evening_refusal WHERE evening < :before", nativeQuery = true)
    int deleteRefusalsBefore(@Param("before") LocalDate before);

    /**
     * The morning e-mail of an evening claimed (V64): marked as sent at {@code now} unless it already went — one upsert, so two runs
     * of the job at once send it once (the one that gets 0 sends nothing). The evening's row is made when the DJ never named it.
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO evening_summary (party_code, evening, morning_sent_at, updated_at) VALUES (:partyCode, :evening, :now, :now)"
            + " ON CONFLICT (party_code, evening) DO UPDATE SET morning_sent_at = EXCLUDED.morning_sent_at"
            + " WHERE evening_summary.morning_sent_at IS NULL", nativeQuery = true)
    int claimMorning(@Param("partyCode") String partyCode, @Param("evening") LocalDate evening, @Param("now") Instant now);

    /** A claimed morning e-mail given back (its send failed): the job's next run that morning tries again. */
    @Transactional
    @Modifying
    @Query(value = "UPDATE evening_summary SET morning_sent_at = NULL WHERE party_code = :partyCode AND evening = :evening",
            nativeQuery = true)
    int releaseMorning(@Param("partyCode") String partyCode, @Param("evening") LocalDate evening);

    /**
     * The parties with at least {@code minimum} guests' requests between {@code from} and {@code to} — the ones still there and the
     * ones kept of the requests gone (a request is in one of them), the DJ's own additions not counted —, at most {@code limit}.
     * {@code idx_song_requests_requested_at} and {@code idx_evening_song_time} find the day's rows.
     */
    @Query(value = "SELECT party_code FROM ("
            + " SELECT party_code FROM song_requests WHERE requested_at >= :from AND requested_at < :to AND NOT added_by_dj"
            + " UNION ALL"
            + " SELECT party_code FROM evening_song WHERE requested_at >= :from AND requested_at < :to AND NOT added_by_dj"
            + ") guests GROUP BY party_code HAVING count(*) >= :minimum ORDER BY party_code LIMIT :limit", nativeQuery = true)
    List<String> busyParties(@Param("from") Instant from, @Param("to") Instant to, @Param("minimum") int minimum,
                             @Param("limit") int limit);
}
