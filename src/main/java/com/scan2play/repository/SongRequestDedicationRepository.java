package com.scan2play.repository;

import com.scan2play.entity.SongRequestDedicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** The guests' dedications (V38): saved with a request, read for the DJ's queue. */
@Repository
public interface SongRequestDedicationRepository extends JpaRepository<SongRequestDedicationEntity, Long> {

    /**
     * The newest {@code perSong} dedications of each of these requests, each song's in the order they came, the songs by id. Bounded:
     * at most {@code perSong} rows a song, the queue's ≤ 300 songs; {@code idx_dedication_request} finds them.
     */
    @Query(value = "SELECT id, song_request_id, text, checked, created_at FROM ("
            + " SELECT d.*, row_number() OVER (PARTITION BY d.song_request_id ORDER BY d.id DESC) AS place"
            + " FROM song_request_dedications d WHERE d.song_request_id IN (:ids)) newest"
            + " WHERE newest.place <= :perSong ORDER BY song_request_id, id", nativeQuery = true)
    List<SongRequestDedicationEntity> findNewest(@Param("ids") Collection<Long> ids, @Param("perSong") int perSong);
}
