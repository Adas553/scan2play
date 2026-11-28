-- V85 — "Już leciało" (DeckSetService.playedLately) asks at every guest's request for the evening's first request of a guest:
-- min(requested_at) of one party's requests of the last 12 h, the DJ's own "+ Dodaj piosenkę" left out. idx_party_decision_time has
-- the decision between the party and the time, so it gives no minimum, and for a party with many requests the planner went through
-- idx_song_requests_requested_at — every party's requests of the last 12 h, tens of thousands on a busy Saturday. This index answers
-- it with one row (an Index Only Scan).
-- Only adds an index; no row is moved. Not CONCURRENTLY: Flyway runs a migration in a transaction (the table is ~30 days of requests).
CREATE INDEX idx_song_requests_party_guest_time ON public.song_requests (party_code, requested_at) WHERE NOT added_by_dj;
