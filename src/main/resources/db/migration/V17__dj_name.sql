-- V17 — who plays (the DJ's name, e.g. "DJ Koko"): one line of at most 60 characters, shown to the guests as "🎧 Gra: DJ Koko".
ALTER TABLE public.party_settings ADD COLUMN dj_name varchar(60);
