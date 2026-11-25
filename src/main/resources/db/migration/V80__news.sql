-- V80 — "Nowości": the panel's card of what is new (PanelNews) and the e-mail of it to the organisers who asked for it.
--   party_settings.news_seen      — the number of the latest news the organiser closed in the panel (PanelNews.NUMBER); 0 = none.
--   party_settings.news_emails    — "Nowości mailem": the organiser's consent to the e-mails of what is new; off until they switch it
--                                   on themselves (the privacy policy: never an e-mail of news without it).
--   party_settings.news_emails_at — when that consent was last given or taken back (the proof of the consent asked for).
--   party_settings.news_sent      — the number of the latest news e-mailed to the organiser; claimed by one UPDATE before the send
--                                   (two sends at once send it once), given back when the send fails.
-- Only adds columns with defaults; no row is moved.
ALTER TABLE public.party_settings ADD COLUMN news_seen integer NOT NULL DEFAULT 0;
ALTER TABLE public.party_settings ADD COLUMN news_emails boolean NOT NULL DEFAULT false;
ALTER TABLE public.party_settings ADD COLUMN news_emails_at timestamptz;
ALTER TABLE public.party_settings ADD COLUMN news_sent integer NOT NULL DEFAULT 0;

-- the send's list: the few organisers who said yes, by the news they have not had
CREATE INDEX idx_party_settings_news_due ON public.party_settings (news_sent, party_code) WHERE news_emails;
