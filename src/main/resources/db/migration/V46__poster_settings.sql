-- V46 — the QR print as the DJ sets it (the poster and the cards of /dj/qr-print), kept with the party so every print, from any device
-- and by the staff, is the same:
--   poster_heading   — the DJ's own heading in place of "Zeskanuj i poproś o piosenkę / Scan to request a song"; null = ours
--   poster_line      — one line of the DJ's own text under it ("Osiemnastka Dawida"); null = none
--   poster_language  — AUTO (the panel's language: a Polish panel prints Polish and English, any other English), BOTH, PL or EN
--   poster_show_*    — the DJ's logo, the profiles, the tip link, the address and the party's code: shown or left off the paper
ALTER TABLE public.party_settings ADD COLUMN poster_heading character varying(40);
ALTER TABLE public.party_settings ADD COLUMN poster_line character varying(60);
ALTER TABLE public.party_settings ADD COLUMN poster_language character varying(4) NOT NULL DEFAULT 'AUTO';
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_poster_language_check
    CHECK (poster_language IN ('AUTO', 'BOTH', 'PL', 'EN'));
ALTER TABLE public.party_settings ADD COLUMN poster_show_logo boolean NOT NULL DEFAULT true;
ALTER TABLE public.party_settings ADD COLUMN poster_show_links boolean NOT NULL DEFAULT true;
ALTER TABLE public.party_settings ADD COLUMN poster_show_tip boolean NOT NULL DEFAULT true;
ALTER TABLE public.party_settings ADD COLUMN poster_show_address boolean NOT NULL DEFAULT true;
