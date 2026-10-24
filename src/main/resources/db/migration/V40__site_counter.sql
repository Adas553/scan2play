-- V40 — how many people come to the site and how far they get, per day (the owner, 2026-10-24: after the first posts on Facebook
-- the only answer was Railway's HTTP log, kept 7 days). Numbers only: no address, no browser, no account — one row per day and
-- event, counted up by SiteCounter (INSERT … ON CONFLICT, atomic). The day is Polish time. Shown to the owner at /dj/stats.
CREATE TABLE public.site_counter (
    day   date        NOT NULL,
    event varchar(20) NOT NULL,
    count integer     NOT NULL,
    CONSTRAINT site_counter_pkey PRIMARY KEY (day, event)
);
