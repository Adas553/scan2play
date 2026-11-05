-- V64 — the automatic e-mails (Brevo): the welcome to a new organiser, once, and the morning summary of a Pro organiser's evening.
--   party_settings.welcome_sent_at — when the welcome e-mail went (claimed just before it is sent, given back when the send fails, so
--                                    the nightly job tries again); null = not yet. The accounts of before count as welcomed: they
--                                    were written to by hand (docs/welcome-email.md) or are not new.
--   party_settings.email_language  — the panel's language ('pl' / 'en') the organiser last opened it in: the e-mails' language. Null
--                                    until the organiser opens the panel after V64 (the e-mails then go in Polish).
--   party_settings.morning_summary — the switch "Poranne podsumowanie" (on for every account; the e-mail itself is Pro's alone).
--   party_settings.email_token     — the secret of the one-click "Nie wysyłaj mi tego" in every e-mail (/email/stop/{token}): 128
--                                    random bits, made by the database for every row, old and new.
--   evening_summary.morning_sent_at — when the morning summary of that evening went: claimed by an upsert (two runs of the job at
--                                    once send it once), given back when the send fails.
--   email_status                   — one row: the last e-mail Brevo took and the last one it did not, for /dj/stats (a key unused for
--                                    90 days expires; a refused key must be seen before the next welcome is lost).
ALTER TABLE public.party_settings ADD COLUMN welcome_sent_at timestamptz;
UPDATE public.party_settings SET welcome_sent_at = now();
ALTER TABLE public.party_settings ADD COLUMN email_language character varying(2);
ALTER TABLE public.party_settings ADD COLUMN morning_summary boolean NOT NULL DEFAULT true;
ALTER TABLE public.party_settings ADD COLUMN email_token character varying(32) NOT NULL
    DEFAULT replace(gen_random_uuid()::text, '-', '');
CREATE UNIQUE INDEX uk_party_settings_email_token ON public.party_settings (email_token);
-- the nightly retry of the welcome: the few new accounts without it
CREATE INDEX idx_party_settings_welcome_due ON public.party_settings (created_at) WHERE welcome_sent_at IS NULL;

ALTER TABLE public.evening_summary ADD COLUMN morning_sent_at timestamptz;

-- the morning job's evenings: every party's guests' requests of one day (song_requests had no index that starts by the time)
CREATE INDEX idx_song_requests_requested_at ON public.song_requests (requested_at);

CREATE TABLE public.email_status (
    id             smallint     PRIMARY KEY CHECK (id = 1),
    last_sent_at   timestamptz,
    last_failed_at timestamptz,
    last_failure   character varying(200)
);
INSERT INTO public.email_status (id) VALUES (1);
