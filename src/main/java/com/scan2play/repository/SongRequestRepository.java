package com.scan2play.repository;

import com.scan2play.entity.SongRequestEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SongRequestRepository extends JpaRepository<SongRequestEntity, Long> {

    /**
     * Finds the top 100 oldest song requests for the DJ dashboard queue (FIFO order).
     * Oldest request is first — DJ sees what will play next at the top of the list.
     *
     * @param partyCode The unique code of the party.
     * @param decisions The list of statuses to include (e.g., ["accepted"]).
     * @return A list of the top 100 matching song requests, ordered by oldest first.
     */
    List<SongRequestEntity> findTop100ByPartyCodeAndDecisionInOrderByRequestedAtAsc(String partyCode, Collection<String> decisions);

    /**
     * Finds the top 5 most recently accepted songs for a specific party's public queue.
     *
     * @param partyCode The unique code of the party.
     * @param decision  The status to filter by (e.g., "accepted").
     * @return A list of the top 5 accepted songs.
     */
    List<SongRequestEntity> findTop5ByPartyCodeAndDecisionOrderByRequestedAtDesc(String partyCode, String decision);

    /**
     * Finds song requests for a specific party filtered by one or more statuses, most recent first, dynamically
     * paginated — the recent requests the AI looks at when it checks for duplicates.
     *
     * @param partyCode The unique code of the party.
     * @param decisions The list of statuses to include.
     * @param pageable  Pagination/limit constraints.
     * @return A list of matching song requests.
     */
    List<SongRequestEntity> findAllByPartyCodeAndDecisionInOrderByRequestedAtDesc(String partyCode, Collection<String> decisions, Pageable pageable);

    /**
     * The party's played and/or rejected requests, the most recent event first — for the DJ history
     * ({@code PlayHistoryService}). The moment of an event is when the request was played, or, for a rejected one or one
     * that was played before V6 (no {@code played_at}), when it was requested. The read is bounded by the pageable and
     * limited to one party and the given decisions; sorting the party's rows on the expression is cheap next to that.
     *
     * @param partyCode The unique code of the party.
     * @param decisions The decisions to include (played, rejected).
     * @param pageable  How many to read.
     * @return The most recent requests, newest event first.
     */
    @Query("SELECT s FROM SongRequestEntity s WHERE s.partyCode = :partyCode AND s.decision IN :decisions "
            + "ORDER BY COALESCE(s.playedAt, s.requestedAt) DESC, s.id DESC")
    List<SongRequestEntity> findHistory(@Param("partyCode") String partyCode,
                                        @Param("decisions") Collection<String> decisions,
                                        Pageable pageable);

    /**
     * Computes a lightweight fingerprint of the queue state (count + maxId).
     * Used for ETag-based 304 Not Modified responses during AJAX polling.
     *
     * @param partyCode The unique code of the party.
     * @param decisions The list of statuses to include.
     * @return A string like "12-487" (count-maxId), or "0-0" if empty.
     */
    @Query("SELECT CONCAT(COUNT(s), '-', COALESCE(MAX(s.id), 0)) " +
           "FROM SongRequestEntity s WHERE s.partyCode = :partyCode AND s.decision IN :decisions")
    String computeFingerprint(@Param("partyCode") String partyCode,
                              @Param("decisions") Collection<String> decisions);

    /**
     * Deletes all song requests associated with a specific party.
     * Used during account deletion to comply with GDPR / Google API data deletion requirements.
     *
     * @param partyCode The unique code of the party.
     */
    void deleteByPartyCode(String partyCode);
}
