-- V84 — "Pokazuj na TV i kiosku, co teraz leci" (docs/ideas.md item 21, 3; the owner, 2026-11-27: the DJ decides whether the room
-- sees it): the "🎶 Teraz: …" bar of the TV screen and the kiosk (NowPlayingService) shown only while this is on; on by default,
-- as it was. Mapped in PartySettingsEntity: the TV and the kiosk read it from the cached settings, no query of their own.
-- Only adds a column with a default; no row is moved.
ALTER TABLE public.party_settings ADD COLUMN now_playing_shown boolean NOT NULL DEFAULT true;
