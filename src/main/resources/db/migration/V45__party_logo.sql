-- V45 — the DJ's logo (plan Pro): an image the organiser uploads in the settings, shown on the guest pages, the TV screen, the QR
-- print and the evening summary. Only the server's own PNG is kept — re-encoded from what was uploaded, no metadata (EXIF) — at most
-- 512 px a side. Goes with the party (and so with the account).
--   party_logo                 — one row per party that has a logo: the PNG, its size and its version (a hash of the PNG: the ETag
--                                and the ?v= of its address)
--   party_settings.logo_version — the same version, so the pages learn from the cached settings whether there is a logo and its
--                                address, without a query of their own; written only with party_logo, in the same transaction
CREATE TABLE public.party_logo (
    party_code character varying(5)  NOT NULL,
    png        bytea                 NOT NULL,
    width      integer               NOT NULL,
    height     integer               NOT NULL,
    version    character varying(64) NOT NULL,
    updated_at timestamptz           NOT NULL,
    CONSTRAINT party_logo_pkey PRIMARY KEY (party_code),
    CONSTRAINT fk_party_logo_party FOREIGN KEY (party_code) REFERENCES public.party_settings (party_code) ON DELETE CASCADE
);

ALTER TABLE public.party_settings ADD COLUMN logo_version character varying(64);
