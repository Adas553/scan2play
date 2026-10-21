-- V39 — whether the AI judged the request (the review of 2026-10-21, S1). A request the AI could not be asked about (an error, a
-- timeout, Gemini's 402 when the credits run out) goes to the DJ unchecked, named with the guest's own words: the guests' list and
-- the TV screen of the hall leave it out — any text a guest typed would be on every phone and on the TV — and the guest who sent it
-- still sees it. The DJ sees every request. A guest's later request for the same song that the AI did judge marks it checked
-- (SongRequestRepository.addVote).
ALTER TABLE public.song_requests ADD COLUMN checked boolean NOT NULL DEFAULT true;

-- The requests already unchecked: they have the note the app wrote instead of the AI's comment (the same text in both languages
-- since 2026-10-01, before the retention's 30 days).
UPDATE public.song_requests SET checked = false
WHERE dj_comment IN ('AI jest chwilowo niedostępne — Twoja prośba trafiła do DJ-a, który sam ją oceni.',
                     'The AI is unavailable right now — your request went to the DJ, who will decide.');
