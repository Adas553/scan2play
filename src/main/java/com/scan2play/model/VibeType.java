package com.scan2play.model;

/**
 * The music of a party, as the DJ sets it ({@link #ANY}: no genre — the AI judges by the DJ's vibe note alone). Stored by name
 * (a check constraint lists them, V16); the order is the order of the lists on the pages. Bachata, salsa and reggaeton are one
 * {@link #LATINO} since V16 — a DJ who wants only one of them says so in the party's vibe note.
 */
public enum VibeType {
    ANY,
    POP_AND_DANCE,
    POLISH_HITS,
    WEDDING_CLASSICS,
    RETRO_80S_90S,
    HITS_2000S_2010S,
    DISCO_POLO,
    FOLK_AND_BIESIADA,
    CLUB_AND_EDM,
    HIP_HOP_AND_RAP,
    RNB_AND_SOUL,
    ROCK_AND_METAL,
    LATINO,
    CHILLOUT_AND_LOUNGE,
    JAZZ,
    CLASSICAL_MUSIC,
    KIDS
}