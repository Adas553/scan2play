-- V49 — "Zmień kod imprezy" (the owner, 2026-10-28: a guest who keeps the code spams every party of the DJ's): the organiser gives the
-- party a new code; the old one stops working — its posters, cards and TV screen with it — and is never given to a party again.
--   The foreign keys on party_code follow a new code (ON UPDATE CASCADE): the party's logo, staff, invitations and library move with
--   its row in one UPDATE (PartyCodeService); song_requests and feedback have no foreign key and are moved by their own UPDATE.
--   party_code_retired — the codes a party had before: no new party and no new code gets one (PartyCodeService.freshCode), so a guest
--                        with an old code never lands in another DJ's party. A code and a day, nothing of the person
ALTER TABLE public.party_logo DROP CONSTRAINT fk_party_logo_party;
ALTER TABLE public.party_logo ADD CONSTRAINT fk_party_logo_party FOREIGN KEY (party_code)
    REFERENCES public.party_settings (party_code) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE public.party_staff DROP CONSTRAINT fk_party_staff_party;
ALTER TABLE public.party_staff ADD CONSTRAINT fk_party_staff_party FOREIGN KEY (party_code)
    REFERENCES public.party_settings (party_code) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE public.staff_invitation DROP CONSTRAINT fk_staff_invitation_party;
ALTER TABLE public.staff_invitation ADD CONSTRAINT fk_staff_invitation_party FOREIGN KEY (party_code)
    REFERENCES public.party_settings (party_code) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE public.dj_library DROP CONSTRAINT fk_dj_library_party;
ALTER TABLE public.dj_library ADD CONSTRAINT fk_dj_library_party FOREIGN KEY (party_code)
    REFERENCES public.party_settings (party_code) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE public.dj_library_track DROP CONSTRAINT fk_dj_library_track_library;
ALTER TABLE public.dj_library_track ADD CONSTRAINT fk_dj_library_track_library FOREIGN KEY (party_code)
    REFERENCES public.dj_library (party_code) ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE public.party_code_retired (
    party_code character varying(5) NOT NULL,
    retired_at timestamptz          NOT NULL DEFAULT now(),
    CONSTRAINT party_code_retired_pkey PRIMARY KEY (party_code)
);
