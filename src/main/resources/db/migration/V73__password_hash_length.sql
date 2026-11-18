-- V73 — room for the password's hash of a later kind. DelegatingPasswordEncoder keeps '{id}' and the hash; bcrypt's is 68
-- characters, but when Spring Security's default kind changes (argon2's '{argon2@SpringSecurity_v5_8}$argon2id$…' is about
-- 130), a login's upgradeEncoding writes the longer one — 100 would refuse it, and the login with it.
ALTER TABLE public.dj_account ALTER COLUMN password_hash TYPE character varying(255);
