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
     * dedications, added by the DJ, from the kiosk. {@code idx_evening_song_party_time} finds them.
     */
    @Query(value = "SELECT song_request_id, song_name, requested_at, played_at, status, votes, tips, dedications, added_by_dj, via_kiosk"
            + " FROM evening_song WHERE party_code = :partyCode AND requested_at >= :from AND requested_at < :to"
            + " ORDER BY requested_at, song_request_id LIMIT :limit", nativeQuery = true)
    List<Object[]> findSongsBetween(@Param("partyCode") String partyCode, @Param("from") Instant from, @Param("to") Instant to,
                                    @Param("limit") int limit);

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
}
