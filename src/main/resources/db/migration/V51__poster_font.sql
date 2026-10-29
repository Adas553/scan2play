-- V51 — the QR print's heading font as the DJ picks it (the editor's "Krój nagłówka"): THEME (the theme's own — the sans, the serif on
-- the elegant theme; every party before), SANS, SERIF or SCRIPT. The heading only; the fonts are the page's (qr-print.css).
ALTER TABLE public.party_settings ADD COLUMN poster_font character varying(6) NOT NULL DEFAULT 'THEME';
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_poster_font_check
    CHECK (poster_font IN ('THEME', 'SANS', 'SERIF', 'SCRIPT'));
