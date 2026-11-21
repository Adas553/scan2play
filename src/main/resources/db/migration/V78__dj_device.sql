-- V78 — "Program na komputerze" (docs/ideas.md item 19): the DJ's computer, paired with the party, sends the songs its DJ program
-- played (VirtualDJ's, Serato's history) so the queue marks them played by itself. The program logs in with a token of its own, made
-- once from a 6-digit code the panel shows ("Połącz komputer") — never the DJ's login. Only the token's SHA-256 is kept: a copy of
-- the table opens no party. "Odłącz" in the panel deletes the row and the token is dead. At most 3 a party (DjDeviceService).
--   name          — what the program says it runs on ("Laptop DJ-a — Windows"), shown in the panel
--   last_seen_at  — the program's last call (kept to a minute: one write a minute at most), shown as "Ostatnio: 21:43"
-- Goes with the party, follows a new party code. Only adds: no rows moved.
CREATE TABLE public.dj_device (
    id           bigserial               NOT NULL,
    party_code   character varying(5)    NOT NULL,
    token_hash   character(64)           NOT NULL,
    name         character varying(60)   NOT NULL,
    created_at   timestamptz             NOT NULL,
    last_seen_at timestamptz,
    CONSTRAINT dj_device_pkey PRIMARY KEY (id),
    CONSTRAINT uk_dj_device_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_dj_device_party FOREIGN KEY (party_code) REFERENCES public.party_settings (party_code)
        ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE INDEX idx_dj_device_party ON public.dj_device (party_code);
