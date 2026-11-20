-- V77 — "Plan imprezy" (Pro, with the wishes' events of V68): the plan of an event's evening, shared by the DJ and the hosts — its
-- moments (the couple's entrance, the first dance, the cake…) with a time, a song and a note, and the hosts' "don't play" / "must
-- play" of that event. The hosts fill it without an account on the event's own link /h/e/{host_token}; the DJ on the event's page.
--   wish_event.host_token  — the secret of the hosts' link (128 bits, as the party's /h/ link; made new: the old one dies); null = none.
--   wish_event.plan_blocked / plan_wanted — the event's two lists (one song or artist a line, as party_settings.host_blocked /
--                            host_wanted); the party's own lists are what the AI and the queue use — "Użyj na imprezie" copies these.
--   wish_event.plan_saved_at / plan_saved_by — the last save, 'dj' or 'hosts' (last save wins; the pages say who and when).
--   event_plan_item        — the moments in their order (position 0, 1, …), at most 30 an event (EventPlanService.MAX_MOMENTS).
-- Deleted with the event (7 days after its date, WishService.purge), so with the party too. Only adds: no rows moved.
ALTER TABLE public.wish_event
    ADD COLUMN host_token    character varying(32),
    ADD COLUMN plan_blocked  character varying(16000),
    ADD COLUMN plan_wanted   character varying(16000),
    ADD COLUMN plan_saved_at timestamptz,
    ADD COLUMN plan_saved_by character varying(5),
    ADD CONSTRAINT uk_wish_event_host_token UNIQUE (host_token);

CREATE TABLE public.event_plan_item (
    event_id  bigint                 NOT NULL,
    position  smallint               NOT NULL,
    label     character varying(60)  NOT NULL,
    at_time   time,
    song      character varying(150),
    note      character varying(300),
    CONSTRAINT event_plan_item_pkey PRIMARY KEY (event_id, position),
    CONSTRAINT fk_event_plan_item_event FOREIGN KEY (event_id) REFERENCES public.wish_event (id) ON DELETE CASCADE
);
