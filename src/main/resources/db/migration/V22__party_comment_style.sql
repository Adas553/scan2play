-- V22 — how the AI words its comment to the guest, as the DJ picks it: classic (the prompt as before), funny, lightly sarcastic,
-- sarcastic, short. Every party so far gets the classic one.
ALTER TABLE public.party_settings ADD COLUMN comment_style varchar(20) NOT NULL DEFAULT 'CLASSIC'
    CONSTRAINT party_settings_comment_style_check
        CHECK (comment_style IN ('CLASSIC', 'FUNNY', 'SARCASTIC_LIGHT', 'SARCASTIC', 'SHORT'));
