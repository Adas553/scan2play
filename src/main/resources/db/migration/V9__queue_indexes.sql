-- V9 — indexes that match the queries (review item 1.5), and two that only duplicated others.
--
-- fallback_track: every query of the queue filters party, playlist and status and reads in play order
-- (FallbackTrackRepository.findByPartyCodeAndPlaylistIdAndStatus with UPCOMING_ORDER — each hand-out, the "up next" list,
-- the version of that list, the counts). The old (party_code, status) index found the party's rows of every playlist,
-- CANCELLED ones of old imports included, and left the sort to the query. The new one is read in order: the next track
-- is its first entry. A filter on (party_code, status) alone (the bulk QUEUED -> CANCELLED of an import) still uses its
-- leading column, so the old index goes.
CREATE INDEX idx_fallback_track_queue
    ON public.fallback_track USING btree (party_code, playlist_id, status, play_order, playlist_position);
DROP INDEX IF EXISTS public.idx_fallback_track_party_status;

-- MAX(fetched_at) of a party: the newest import, which a new round re-queues (requeuePlayedTracks) and whose age decides a
-- refresh of the playlist on a hand-out (findLatestFetchedAt). It also serves the nightly purge (fetched_at < cutoff) by a
-- skip scan.
CREATE INDEX idx_fallback_track_party_fetched ON public.fallback_track USING btree (party_code, fetched_at);

-- Duplicates: owner_id has a UNIQUE constraint (an index of its own), and (party_code) is the leading column of
-- idx_party_decision_time — each one cost a write and served nothing the other did not.
DROP INDEX IF EXISTS public.idx_owner_id;
DROP INDEX IF EXISTS public.idx_party_code;
