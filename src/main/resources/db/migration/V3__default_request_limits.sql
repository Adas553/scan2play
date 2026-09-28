-- V3 — repair party_settings rows that were created with zero rate limits.
--
-- PartySettingsEntity.builder() used to ignore the field defaults (Lombok @Builder without
-- @Builder.Default), so every party created through PartySettingsCommandService#createNewParty got
-- request_limit = 0 and cooldown_minutes = 0 instead of 2 and 3. Zero switches guest rate limiting
-- off entirely (with a 0-minute cooldown earlier requests expire immediately), so one guest could
-- flood the queue and burn the small daily YouTube search quota. The DJ form only accepts values >= 1,
-- so 0 was never a deliberate choice.
--
-- Not touched: duplicate_check_window — 0 is a valid choice there ("don't check duplicates") and the
-- form allows it, so an existing 0 cannot be told apart from a deliberate one.
UPDATE public.party_settings SET request_limit = 2 WHERE request_limit < 1;
UPDATE public.party_settings SET cooldown_minutes = 3 WHERE cooldown_minutes < 1;
