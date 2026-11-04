-- V59 — the look of the guests' pages as the DJ picks it ("Motyw dla gości" in "Ustawienia imprezy"): the party page, the result, the
-- "party ended" page, the kiosk and the TV screen. Every theme is dark (a dark hall, guests' phones); a theme changes the colour tokens
-- of the page, adds one ornament above the card and, in some, the face of "🎧 Gra: …" — never the statuses ("Twoja", 👍, "NIE").
--   party_settings.guest_theme — CLASSIC (as before: black and the logo's cyan), CHRISTMAS (navy, ice blue, snowflakes), SCARY
--                                (Halloween: violet black, pumpkin orange, webs and bats), CONFETTI (any party, a birthday: lilac,
--                                confetti), WEDDING (plum black, powder pink, a sprig)
ALTER TABLE public.party_settings ADD COLUMN guest_theme character varying(10) NOT NULL DEFAULT 'CLASSIC';
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_guest_theme_check
    CHECK (guest_theme IN ('CLASSIC', 'CHRISTMAS', 'SCARY', 'CONFETTI', 'WEDDING'));
