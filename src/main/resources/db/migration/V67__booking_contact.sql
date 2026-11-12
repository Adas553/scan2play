-- V67 — the DJ's contact for bookings (Pro, with the profiles): an e-mail address and a phone number the guests see under "Zarezerwuj
-- tego DJ-a na swoją imprezę" (a request that reached the DJ, the page "party ended") — the guest writes or calls the DJ straight;
-- Scan2Play keeps nothing of the guest. Each as util/BookingContact wrote it, or null.
ALTER TABLE public.party_settings
    ADD COLUMN booking_email varchar(254),
    ADD COLUMN booking_phone varchar(20);
