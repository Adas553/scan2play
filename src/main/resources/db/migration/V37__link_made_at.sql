-- V37 — the hosts' link (/h/{token}, V29) and the staff's invitation link (/join/{token}, V30) work 30 days from when the DJ made
-- them, as an invitation by address (staff_invitation, V34): a link given for one event does not open the party's lists or its
-- staff for good (the review of 2026-10-14). A link that is there now counts from this migration.
ALTER TABLE public.party_settings ADD COLUMN host_token_made_at timestamptz;
ALTER TABLE public.party_settings ADD COLUMN staff_token_made_at timestamptz;
UPDATE public.party_settings SET host_token_made_at = now() WHERE host_token IS NOT NULL;
UPDATE public.party_settings SET staff_token_made_at = now() WHERE staff_token IS NOT NULL;
