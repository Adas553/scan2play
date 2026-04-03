package com.scan2play.repository;

import com.scan2play.entity.SongRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SongRequestRepository extends JpaRepository<SongRequestEntity, Long> {


    /**
     * Finds the top 100 most recent song requests for the DJ dashboard queue.
     *
     * @param partyCode The unique code of the party.
     * @param decisions The list of statuses to include (e.g., ["accepted"]).
     * @return A list of the top 100 matching song requests, ordered by most recent.
     */
    List<SongRequestEntity> findTop100ByPartyCodeAndDecisionInOrderByRequestedAtDesc(String partyCode, Collection<String> decisions);

    /**
     * Finds the top 5 most recently accepted songs for a specific party's public queue.
     *
     * @param partyCode The unique code of the party.
     * @param decision  The status to filter by (e.g., "accepted").
     * @return A list of the top 5 accepted songs.
     */
    List<SongRequestEntity> findTop5ByPartyCodeAndDecisionOrderByRequestedAtDesc(String partyCode, String decision);

    /**
     * Finds song requests for a specific party filtered by one or more statuses, limited to the top 50 most recent.
     *
     * @param partyCode The unique code of the party.
     * @param decisions The list of statuses to include (e.g., ["accepted"]).
     * @return A list of the top 50 matching song requests, ordered by most recent.
     */
    List<SongRequestEntity> findTop50ByPartyCodeAndDecisionInOrderByRequestedAtDesc(String partyCode, Collection<String> decisions);

    /**
     * Finds song requests for a specific party filtered by one or more statuses, dynamically paginated.
     *
     * @param partyCode The unique code of the party.
     * @param decisions The list of statuses to include.
     * @param pageable  Pagination/limit constraints.
     * @return A list of matching song requests.
     */
    List<SongRequestEntity> findAllByPartyCodeAndDecisionInOrderByRequestedAtDesc(String partyCode, Collection<String> decisions, org.springframework.data.domain.Pageable pageable);

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
}
