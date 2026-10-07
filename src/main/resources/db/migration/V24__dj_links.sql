-- V24 — the DJ's profiles, shown to the guests under "🎧 Gra: DJ Koko" and on the QR print: Instagram, Facebook, TikTok. Each one an
-- https address on its own site as the app wrote it (util/SocialLinks), or null.
ALTER TABLE public.party_settings ADD COLUMN instagram_url varchar(200);
ALTER TABLE public.party_settings ADD COLUMN facebook_url varchar(200);
ALTER TABLE public.party_settings ADD COLUMN tiktok_url varchar(200);
