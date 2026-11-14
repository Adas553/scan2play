-- V70 — the white badge behind the DJ's logo on the QR print (the editor's "Białe tło pod logo"): AUTO (on the club's paper and on a
-- paper of the DJ's own; every party before), ALWAYS (a logo with a white background of its own reads as a rounded badge on any
-- paper) or NEVER. The badge's room is the logo's on every paper (qr-print.css), so it only colours it.
ALTER TABLE public.party_settings ADD COLUMN poster_logo_badge character varying(6) NOT NULL DEFAULT 'AUTO';
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_poster_logo_badge_check
    CHECK (poster_logo_badge IN ('AUTO', 'ALWAYS', 'NEVER'));
