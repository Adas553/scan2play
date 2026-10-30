-- V55 — the party opens and closes by itself at the moments the DJ set ("Godziny imprezy"): the next open and the next close, each
-- optional (null = none). The job that applies them (PartySettingsCommandService.applyDueSchedule, every minute) reads only these two
-- columns; a weekly rule, later, would write its next moment into them after each one. Partial indexes: nearly every party has none,
-- and the job asks only for the ones that are due.
ALTER TABLE public.party_settings ADD COLUMN next_open_at timestamp with time zone;
ALTER TABLE public.party_settings ADD COLUMN next_close_at timestamp with time zone;
CREATE INDEX idx_party_settings_next_open_at ON public.party_settings (next_open_at) WHERE next_open_at IS NOT NULL;
CREATE INDEX idx_party_settings_next_close_at ON public.party_settings (next_close_at) WHERE next_close_at IS NOT NULL;
