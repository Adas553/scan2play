-- V29 — the hosts' lists (the couple at a wedding, the host of a party, the pub's owner): songs not to play and songs to play,
-- one song or artist per line as util/SongList keeps them (at most 100 lines of 150 characters), or null. A request for a song on
-- the first is refused without reaching the DJ; one on the second is accepted even when the AI would not take it, and marked ⭐
-- in the DJ's queue. host_token: the secret of the link the DJ gives the hosts (/h/{token}) to fill the lists without an
-- account — null until the DJ makes one, a new one makes the old link dead.
ALTER TABLE public.party_settings ADD COLUMN host_blocked varchar(16000);
ALTER TABLE public.party_settings ADD COLUMN host_wanted varchar(16000);
ALTER TABLE public.party_settings ADD COLUMN host_token varchar(32);
CREATE UNIQUE INDEX uk_party_settings_host_token ON public.party_settings (host_token);
