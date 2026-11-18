-- V72 — the login with an e-mail address and a password, beside Google's (PasswordAccountService). One row an account; the
-- account's id in the rest of the app (party_settings.owner_id, party_staff.user_id, push_subscription.owner_id …) is
-- 'local:' || id — a Google subject is digits only, so the two never meet. Nothing secret is kept in the clear: the password as a
-- bcrypt hash, the links of the e-mails (the address's confirmation, a new password) as the SHA-256 of their secret.
--   email              — the address, lower case; one account an address (the unique index)
--   password_hash      — Spring Security's DelegatingPasswordEncoder form: '{bcrypt}$2a$…'
--   display_name       — what the person wrote as their name (the staff see it, as a Google account's name)
--   email_verified_at  — when the address was confirmed by the e-mail's link; null: the account cannot log in yet
--   verify_token_hash  — the confirmation link's secret, hashed; with verify_expires_at (48 h); null once confirmed
--   reset_token_hash   — "Nie pamiętam hasła": the new password's link, hashed; with reset_expires_at (1 h); null once used
CREATE TABLE public.dj_account (
    id                bigserial               NOT NULL,
    email             character varying(320)  NOT NULL,
    password_hash     character varying(100)  NOT NULL,
    display_name      character varying(100),
    created_at        timestamptz             NOT NULL,
    email_verified_at timestamptz,
    verify_token_hash character varying(64),
    verify_expires_at timestamptz,
    reset_token_hash  character varying(64),
    reset_expires_at  timestamptz,
    CONSTRAINT dj_account_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uk_dj_account_email ON public.dj_account (email);
-- the links of the e-mails find their account by the hash; most rows have none
CREATE UNIQUE INDEX uk_dj_account_verify_token ON public.dj_account (verify_token_hash) WHERE verify_token_hash IS NOT NULL;
CREATE UNIQUE INDEX uk_dj_account_reset_token ON public.dj_account (reset_token_hash) WHERE reset_token_hash IS NOT NULL;
-- a new account's address is checked against the Google accounts' (one address, one way to log in)
CREATE INDEX idx_party_settings_owner_email_lower ON public.party_settings (lower(owner_email)) WHERE owner_email IS NOT NULL;
