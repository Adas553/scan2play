-- V23 — a skip ("Pomiń") keeps the AI's comment from now on, and a restore gives the request back with it; skipped_at alone says it
-- was skipped (the history shows that in the page's language). The fixed English notes the skips and restores wrote before in its
-- place go: the AI's comment under them is lost, and an English note on a Polish page said nothing the history does not.
UPDATE public.song_requests SET dj_comment = NULL
    WHERE dj_comment IN ('Skipped by the DJ ⏭', 'Restored by the DJ ↩');
