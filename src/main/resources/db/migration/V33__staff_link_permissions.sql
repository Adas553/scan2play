-- V33 — the role of the staff's invitation link (the owner, 2026-10-10): the organiser picks what whoever joins by the link may do
-- when making it — the names of the permissions (com.scan2play.model.StaffPermission), comma-separated, as party_staff.permissions.
-- NULL while there is no link. A new role is a new link (the old one dead), so a link sent as "Podgląd" never starts to give more.
-- The links made before give what they gave until now: the role "Obsługa kolejki" (StaffRole.QUEUE).
ALTER TABLE public.party_settings ADD COLUMN staff_link_permissions varchar(200);
UPDATE public.party_settings SET staff_link_permissions = 'QUEUE,TIPS,CLEAR_QUEUE,OPEN_CLOSE,HISTORY' WHERE staff_token IS NOT NULL;
