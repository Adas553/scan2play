-- V69 — "Życzenia przed imprezą": the look of an event's guests' page (/w/{token}) is the event's own, picked when it is made and
-- changed on its card — not the party's "Motyw dla gości" (V59), which a DJ changes during a party: a Halloween look switched on
-- tonight must not reach an invitation to a wedding next month.
--   wish_event.guest_theme — one of the guests' looks of V59; the events made before V69 keep the classic one. A new look of the
--                            guests' pages goes into both CHECKs: this one and party_settings_guest_theme_check.
ALTER TABLE public.wish_event ADD COLUMN guest_theme character varying(10) NOT NULL DEFAULT 'CLASSIC';
ALTER TABLE public.wish_event ADD CONSTRAINT wish_event_guest_theme_check
    CHECK (guest_theme IN ('CLASSIC', 'CHRISTMAS', 'SCARY', 'CONFETTI', 'WEDDING'));
