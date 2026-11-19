-- V75 — "Pytanie do gości": the guests' results hidden until the DJ shows them (a game at a wedding: "Kto pierwszy na parkiet?" told
-- at the end, the TV the moment of it). results_hidden: the guests' page and the TV show no numbers but how many voted; the DJ's page
-- shows them always. revealed_at: when the DJ last showed them — the guests and the TV see a question a quarter of an hour after it
-- ended, or after its results were shown, whichever is later (PollService.shown). Every question made before shows its results, as
-- it did.
ALTER TABLE public.party_poll ADD COLUMN results_hidden boolean NOT NULL DEFAULT false;
ALTER TABLE public.party_poll ADD COLUMN revealed_at timestamptz;
