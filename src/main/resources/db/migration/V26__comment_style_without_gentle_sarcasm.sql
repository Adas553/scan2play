-- V26 — the comment style "sarcastic (gentle)" goes (the owner, 2026-10-08: "sarcastic" is enough). A party that had it gets
-- "sarcastic", and the database takes it no more.
UPDATE public.party_settings SET comment_style = 'SARCASTIC' WHERE comment_style = 'SARCASTIC_LIGHT';

ALTER TABLE public.party_settings DROP CONSTRAINT party_settings_comment_style_check;
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_comment_style_check
    CHECK (comment_style IN ('CLASSIC', 'FUNNY', 'SARCASTIC', 'SHORT'));
