-- V65 — the comment style "trivia" (CommentStyle.TRIVIA): a fact about the accepted song instead of a joke. The check takes it.

ALTER TABLE public.party_settings DROP CONSTRAINT party_settings_comment_style_check;
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_comment_style_check
    CHECK (comment_style IN ('CLASSIC', 'FUNNY', 'SARCASTIC', 'TRIVIA', 'SHORT'));
