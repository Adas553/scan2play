-- V53 — the QR print's paper as the DJ picks it (the editor's "Kolor tła"): null = the theme's own; else '#rrggbb', made a light tint
-- by the page (qr-print.js mixes it towards white — little ink, the words readable on it), so any colour picked prints pale.
ALTER TABLE public.party_settings ADD COLUMN poster_paper character varying(7);
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_poster_paper_check
    CHECK (poster_paper IS NULL OR poster_paper ~ '^#[0-9a-f]{6}$');
