-- V74 — "Pytanie do gości" (Pro): the DJ asks the room a question of their own ("Disco polo — tak czy nie?") with two to four
-- answers; the guests answer on the party page, one vote each, and the DJ sees the results live (the TV screen shows them too).
-- Nothing of the guest is kept: a random token of their session (not their address, not their device) and the answer they picked.
--   party_poll       — one question of a party. opened_at / closed_at say its state: neither = saved for later, opened_at alone =
--                      asked now, both = ended. One question at a time is asked (the partial unique index): asking another ends the
--                      one before in the same transaction. vote_count: every voter, raised in the transaction that takes a new one,
--                      under the row's lock, so the ceiling holds under guests voting at once.
--   party_poll_vote  — one guest's answer: choice is the answer's index (0 … 3); the guest may change it while the question is asked.
-- Deleted by the DJ, with the party (and follow its code: ON UPDATE CASCADE), and 90 days after they were made (PollService.purge).
CREATE TABLE public.party_poll (
    id          bigserial              NOT NULL,
    party_code  character varying(5)   NOT NULL,
    question    character varying(120) NOT NULL,
    answers     character varying(40)[] NOT NULL,
    vote_count  integer                NOT NULL DEFAULT 0,
    created_at  timestamptz            NOT NULL DEFAULT now(),
    opened_at   timestamptz,
    closed_at   timestamptz,
    CONSTRAINT party_poll_pkey PRIMARY KEY (id),
    CONSTRAINT ck_party_poll_answers CHECK (cardinality(answers) BETWEEN 2 AND 4),
    CONSTRAINT ck_party_poll_closed CHECK (closed_at IS NULL OR opened_at IS NOT NULL),
    CONSTRAINT fk_party_poll_party FOREIGN KEY (party_code) REFERENCES public.party_settings (party_code)
        ON DELETE CASCADE ON UPDATE CASCADE
);
-- a party's questions, the newest first: the DJ's page, the count a party keeps
CREATE INDEX idx_party_poll_party ON public.party_poll (party_code, id);
-- the question asked now: one a party at most, found at once by the guests' page and the TV
CREATE UNIQUE INDEX uk_party_poll_asked ON public.party_poll (party_code) WHERE opened_at IS NOT NULL AND closed_at IS NULL;
-- the nightly purge
CREATE INDEX idx_party_poll_created ON public.party_poll (created_at);

CREATE TABLE public.party_poll_vote (
    poll_id   bigint                NOT NULL,
    voter     character varying(32) NOT NULL,
    choice    smallint              NOT NULL,
    voted_at  timestamptz           NOT NULL DEFAULT now(),
    CONSTRAINT party_poll_vote_pkey PRIMARY KEY (poll_id, voter),
    CONSTRAINT ck_party_poll_vote_choice CHECK (choice BETWEEN 0 AND 3),
    CONSTRAINT fk_party_poll_vote_poll FOREIGN KEY (poll_id) REFERENCES public.party_poll (id) ON DELETE CASCADE
);

-- The new permission POLLS goes to everyone who had everything there was to hand over (the role "Współorganizator"), as WISHES did
-- in V68: their set stays the whole set. Every other set is left as it is — the organiser ticked it.
UPDATE public.party_staff SET permissions = permissions || ',POLLS'
    WHERE permissions = 'QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY,VIBE,LIMITS,HOST_LISTS,SUMMARY,KIOSK,WISHES';
UPDATE public.party_settings SET staff_link_permissions = staff_link_permissions || ',POLLS'
    WHERE staff_link_permissions = 'QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY,VIBE,LIMITS,HOST_LISTS,SUMMARY,KIOSK,WISHES';
UPDATE public.staff_invitation SET permissions = permissions || ',POLLS'
    WHERE permissions = 'QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY,VIBE,LIMITS,HOST_LISTS,SUMMARY,KIOSK,WISHES';
