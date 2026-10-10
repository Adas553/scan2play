-- V32 — what each person of a party's staff may do (the owner, 2026-10-10): the organiser picks a role or ticks the permissions
-- one by one; the names of the permissions (com.scan2play.model.StaffPermission), comma-separated. The people who joined before get
-- the role "Obsługa kolejki" (StaffRole.QUEUE) — exactly what every person of the staff could do until now.
ALTER TABLE public.party_staff ADD COLUMN permissions varchar(200) NOT NULL DEFAULT 'QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY';
ALTER TABLE public.party_staff ALTER COLUMN permissions DROP DEFAULT;

-- the organiser's name from their Google account, kept when they open the panel: the staff see the party by it when it has no
-- "Kto gra" (not by its code)
ALTER TABLE public.party_settings ADD COLUMN owner_name varchar(100);
