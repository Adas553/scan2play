-- V52 — four more looks of the QR print (the editor's "Motyw"): RUSTIC (a barn wedding, boho — kraft, olive, a stitched frame), GALA
-- (a ball, New Year's Eve, a palace wedding — ivory, gold, a double frame), NEON (a club, an 18th — white, a gradient frame), CONFETTI
-- (a birthday — white, coloured dots in the frame's band). The check of V47 made again with them; poster_theme varchar(10) holds them.
ALTER TABLE public.party_settings DROP CONSTRAINT party_settings_poster_theme_check;
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_poster_theme_check
    CHECK (poster_theme IN ('CLASSIC', 'ELEGANT', 'CLUB', 'PASTEL', 'RUSTIC', 'GALA', 'NEON', 'CONFETTI'));
