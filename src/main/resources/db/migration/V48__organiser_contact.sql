-- V48 — a first contact with a new organiser: their address to write to, when the account came, and the panel's welcome card.
--   owner_email          — the organiser's verified address from their Google account, kept when they open the panel (as owner_name,
--                          V32); never the staff's. To write to them about the account and help, nothing else. Gone with the account
--                          (the row). Null until the organiser opens the panel after V48.
--   created_at           — when the party (the account) was made; null for the parties of before, whose day nobody kept — they are
--                          not "new" on /dj/stats
--   welcome_dismissed_at — when the organiser closed the panel's welcome card ("Pierwszy raz? Napisz do nas"), null = shown. The
--                          parties of before count as closed: the card is for a new account
ALTER TABLE public.party_settings ADD COLUMN owner_email character varying(320);
ALTER TABLE public.party_settings ADD COLUMN created_at timestamptz;
ALTER TABLE public.party_settings ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE public.party_settings ADD COLUMN welcome_dismissed_at timestamptz;
UPDATE public.party_settings SET welcome_dismissed_at = now();
