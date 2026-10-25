-- V43 — the DJ's library: an M3U's name kept the other way round is marked as such. The programs write "Artist - Title" or
-- "Title - Artist", so each name is kept both ways; read the other way round, an artist's name is the title ("Perfect" for
-- "Perfect - Autobiografia"), and "Ed Sheeran - Perfect" would be "? Podobny" by it. A swapped key may say "✓" (the title and an artist
-- both the same), never "?" (LibraryKey.markIn).
--   dj_library_track.swapped — the key of a name read the other way round; false for every key before (uploaded again, an M3U's
--                              swapped keys are marked)
ALTER TABLE public.dj_library_track ADD COLUMN swapped boolean NOT NULL DEFAULT false;
