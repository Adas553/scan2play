-- V16 — the party's vibe: new vibes (Polish hits, the 2000s/2010s, R&B & soul, folk / biesiada, kids); bachata & kizomba, salsa &
-- timba and reggaeton & dancehall are one LATINO; and the DJ's own words about the vibe (vibe_note, one line of at most 150
-- characters), which the AI is given and the guests are shown. The old constraint goes first: it does not let LATINO in.
ALTER TABLE public.party_settings DROP CONSTRAINT IF EXISTS party_settings_global_vibe_check;
UPDATE public.party_settings SET global_vibe = 'LATINO'
    WHERE global_vibe IN ('BACHATA_AND_KIZOMBA', 'SALSA_AND_TIMBA', 'REGGAETON_AND_DANCEHALL');
ALTER TABLE public.party_settings ADD CONSTRAINT party_settings_global_vibe_check
    CHECK (((global_vibe)::text = ANY ((ARRAY['ANY'::character varying, 'POP_AND_DANCE'::character varying,
        'POLISH_HITS'::character varying, 'WEDDING_CLASSICS'::character varying, 'RETRO_80S_90S'::character varying,
        'HITS_2000S_2010S'::character varying, 'DISCO_POLO'::character varying, 'FOLK_AND_BIESIADA'::character varying,
        'CLUB_AND_EDM'::character varying, 'HIP_HOP_AND_RAP'::character varying, 'RNB_AND_SOUL'::character varying,
        'ROCK_AND_METAL'::character varying, 'LATINO'::character varying, 'CHILLOUT_AND_LOUNGE'::character varying,
        'JAZZ'::character varying, 'CLASSICAL_MUSIC'::character varying, 'KIDS'::character varying])::text[])));
ALTER TABLE public.party_settings ADD COLUMN vibe_note varchar(150);
