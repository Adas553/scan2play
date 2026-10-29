-- V50 — the QR print's number of tables (the editor's "Liczba stołów"): the cards and the table tents of /dj/qr-print numbered
-- 1 … N, as many of them as there are tables; null = no numbers (eight cards, one tent). Kept with the party as V46's words: a reprint
-- gives the same set. 50 at most — 50 tents are 50 sheets.
ALTER TABLE public.party_settings ADD COLUMN poster_tables integer;
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_poster_tables_check
    CHECK (poster_tables IS NULL OR poster_tables BETWEEN 1 AND 50);
