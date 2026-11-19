-- V76 — "Pytanie do gości": whether the TV screen shows the question (on_tv, the default: as before) or keeps the guests' most wanted
-- songs while the question is asked — the DJ's switch on each question, any time. The guests' phones are not changed by it: the
-- question stays above their form. Switched to the TV again, revealed_at is set (as "Pokaż wyniki gościom" sets it): the question
-- stays on the screens a quarter of an hour from then, though it ended long before (PollService.shown).
ALTER TABLE public.party_poll ADD COLUMN on_tv boolean NOT NULL DEFAULT true;
