-- V27 — the DJ's tip link: the DJ's page on a service that takes tips (Revolut, PayPal, buycoffee.to, Suppi, Tipply, Buy Me a
-- Coffee, Ko-fi), an https address as the app wrote it (util/TipLinks), or null. The guests pay there, straight to the DJ.
ALTER TABLE public.party_settings ADD COLUMN tip_url varchar(200);
