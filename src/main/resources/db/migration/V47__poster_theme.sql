-- V47 — the QR print's look as the DJ sets it (the poster and the cards of /dj/qr-print), kept with the party as V46's words:
--   poster_theme  — CLASSIC (white, as before), ELEGANT (cream, a serif heading, a double frame), CLUB (dark, the code on a white tile),
--                   PASTEL (a soft pink, rounded)
--   poster_accent — the DJ's colour for the heading and the frame, '#rrggbb'; null = the theme's own. The page darkens or lightens it
--                   until it reads on the theme's paper (qr-print.js), so a pale yellow never prints unreadable
ALTER TABLE public.party_settings ADD COLUMN poster_theme character varying(10) NOT NULL DEFAULT 'CLASSIC';
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_poster_theme_check
    CHECK (poster_theme IN ('CLASSIC', 'ELEGANT', 'CLUB', 'PASTEL'));
ALTER TABLE public.party_settings ADD COLUMN poster_accent character varying(7);
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_poster_accent_check
    CHECK (poster_accent IS NULL OR poster_accent ~ '^#[0-9a-f]{6}$');
