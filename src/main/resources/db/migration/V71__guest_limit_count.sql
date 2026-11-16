-- V71 — the guests' limits that must outlive a restart (GuestLimitStore): a party's requests of its 24 hours (GuestRequestLimiter,
-- kind PARTY_DAY, key = the party's code) and the Free plan's AI checks of an evening (FreeAiLimit, kind FREE_AI, key = the party
-- and the evening). They were in memory alone, so a deploy during a party gave it a new day. Numbers only: no guest's address is
-- ever written here (the address windows stay in memory). The application writes the rows whose count changed once a minute and
-- reads the table at its start; a row is deleted with the write after its window has ended.
CREATE TABLE public.guest_limit_count (
    kind       varchar(10) NOT NULL,
    key        varchar(64) NOT NULL,
    started_at timestamptz NOT NULL,
    count      integer     NOT NULL,
    CONSTRAINT guest_limit_count_pkey PRIMARY KEY (kind, key)
);
