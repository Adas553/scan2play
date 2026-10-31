-- V56 — the venue's kiosk: a tablet at the bar where guests without a phone ask for a song and vote ("Kiosk", Pro). kiosk_token: the
-- secret of its link (/k/{token}) — null until made, a new one makes the old link dead; it does not run out (a tablet stays in the
-- venue), it is taken away by hand. via_kiosk: the request came from the kiosk — the DJ's queue marks it.
ALTER TABLE public.party_settings ADD COLUMN kiosk_token varchar(32);
CREATE UNIQUE INDEX uk_party_settings_kiosk_token ON public.party_settings (kiosk_token);
ALTER TABLE public.song_requests ADD COLUMN via_kiosk boolean NOT NULL DEFAULT false;

-- The new permission KIOSK goes to everyone who had everything there was to hand over (the role "Współorganizator"): their set stays
-- the whole set, as it was given. Every other set is left as it is — the organiser ticked it (StaffPermission, StaffRole).
UPDATE public.party_staff SET permissions = permissions || ',KIOSK'
    WHERE permissions = 'QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY,VIBE,LIMITS,HOST_LISTS,SUMMARY';
UPDATE public.party_settings SET staff_link_permissions = staff_link_permissions || ',KIOSK'
    WHERE staff_link_permissions = 'QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY,VIBE,LIMITS,HOST_LISTS,SUMMARY';
UPDATE public.staff_invitation SET permissions = permissions || ',KIOSK'
    WHERE permissions = 'QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY,VIBE,LIMITS,HOST_LISTS,SUMMARY';
