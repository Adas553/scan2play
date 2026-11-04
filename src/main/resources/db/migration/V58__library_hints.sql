-- V58 — the guests' song suggestions marked by the DJ's library: of the suggestions a guest is shown as they type (iTunes', asked by
-- the browser), the songs the DJ has come first, marked "✓ DJ ma". Only "✓" is said, for at most a few suggestions at a time — the
-- library itself is never listed to a guest. Nothing is refused: a song the DJ has not is asked for as before.
--   party_settings.library_hints — the organiser lets the suggestions be marked; on by default (it works only with a library and the
--                                  plan Pro), off in the library's card ("Podpowiedzi gości")
ALTER TABLE public.party_settings ADD COLUMN library_hints boolean NOT NULL DEFAULT true;
