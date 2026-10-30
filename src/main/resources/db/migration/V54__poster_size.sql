-- V54 — the QR print's heading size as the DJ picks it (the editor's "Rozmiar nagłówka"): SMALL, NORMAL (as the theme has it; every
-- party before) or LARGE — the heading only, scaled by the page (qr-print.css --heading-scale); a long one still fitted to its lines.
ALTER TABLE public.party_settings ADD COLUMN poster_size character varying(6) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_poster_size_check
    CHECK (poster_size IN ('SMALL', 'NORMAL', 'LARGE'));
