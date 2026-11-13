-- V68 — "Życzenia przed imprezą" (Pro): before an event the guests open a link (on the invitation, in an e-mail, in a chat) and
-- write the songs they want to hear; the DJ sees them counted, marked by the library, and moves the ones to play to "⭐ Koniecznie
-- zagrać". Nothing of the guest is kept: the song as typed and how many wrote it.
--   wish_event  — one event of a party: a wedding, an 18th, a company party. A party has one code for every event, so the wishes
--                 belong to an event of their own (a DJ collects for two weddings on two Saturdays at once). token: the secret of
--                 the guests' link /w/{token}, 128 bits as the hosts' (made new: the old link dies). wish_count: every wish written,
--                 raised in the statement that takes one, so the event's ceiling holds under guests writing at once.
--   guest_wish  — one row per song of an event: wish_key (util/LibraryKey: the title without its version, the artists sorted — the
--                 same song typed two ways is one row; a line without " - " is keyed by its words), song_name as the first guest
--                 wrote it, wishes how many wrote it.
-- Deleted 7 days after the event's date (WishService.purge), with the event, with the party (and its code: ON UPDATE CASCADE).
CREATE TABLE public.wish_event (
    id          bigserial             NOT NULL,
    party_code  character varying(5)  NOT NULL,
    token       character varying(32) NOT NULL,
    name        character varying(80) NOT NULL,
    event_date  date                  NOT NULL,
    closed      boolean               NOT NULL DEFAULT false,
    wish_count  integer               NOT NULL DEFAULT 0,
    created_at  timestamptz           NOT NULL DEFAULT now(),
    CONSTRAINT wish_event_pkey PRIMARY KEY (id),
    CONSTRAINT uk_wish_event_token UNIQUE (token),
    CONSTRAINT fk_wish_event_party FOREIGN KEY (party_code) REFERENCES public.party_settings (party_code)
        ON DELETE CASCADE ON UPDATE CASCADE
);
-- a party's events by date: the DJ's page, the count of the open ones
CREATE INDEX idx_wish_event_party_date ON public.wish_event (party_code, event_date);
-- the nightly purge: the events whose date has passed
CREATE INDEX idx_wish_event_date ON public.wish_event (event_date);

CREATE TABLE public.guest_wish (
    event_id   bigint                 NOT NULL,
    wish_key   character varying(401) NOT NULL,
    song_name  character varying(150) NOT NULL,
    wishes     integer                NOT NULL DEFAULT 1,
    first_at   timestamptz            NOT NULL DEFAULT now(),
    last_at    timestamptz            NOT NULL DEFAULT now(),
    CONSTRAINT guest_wish_pkey PRIMARY KEY (event_id, wish_key),
    CONSTRAINT fk_guest_wish_event FOREIGN KEY (event_id) REFERENCES public.wish_event (id) ON DELETE CASCADE
);

-- The new permission WISHES goes to everyone who had everything there was to hand over (the role "Współorganizator"), as KIOSK did
-- in V56: their set stays the whole set. Every other set is left as it is — the organiser ticked it.
UPDATE public.party_staff SET permissions = permissions || ',WISHES'
    WHERE permissions = 'QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY,VIBE,LIMITS,HOST_LISTS,SUMMARY,KIOSK';
UPDATE public.party_settings SET staff_link_permissions = staff_link_permissions || ',WISHES'
    WHERE staff_link_permissions = 'QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY,VIBE,LIMITS,HOST_LISTS,SUMMARY,KIOSK';
UPDATE public.staff_invitation SET permissions = permissions || ',WISHES'
    WHERE permissions = 'QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY,VIBE,LIMITS,HOST_LISTS,SUMMARY,KIOSK';
