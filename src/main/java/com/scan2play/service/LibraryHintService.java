package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.LibraryMark;
import com.scan2play.model.PlanFeature;
import com.scan2play.util.LibraryKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The guests' song suggestions marked by the DJ's library (V58): of the few suggestions a guest is shown as they type, the browser
 * asks which the DJ has, and puts those first with "✓ DJ ma" (song-autocomplete.js). Only "✓" is told — never "? Podobny", a guess a
 * guest would read as a promise — and for at most {@value #MOST_SONGS} songs at a time: the library itself is never listed. Nothing is
 * refused by it; a song the DJ has not is asked for as before.
 */
@Service
@RequiredArgsConstructor
public class LibraryHintService {

    /** The most songs one ask may name: the suggestions shown (8), with room. */
    public static final int MOST_SONGS = 10;

    /** The longest name of a song taken: an artist and a title, with room (a key is cut to its columns anyway). */
    static final int LONGEST_SONG = 300;

    private final DjLibraryService libraryService;
    private final PlanService planService;

    /**
     * Whether the party's guests have their suggestions marked: an open party whose organiser has the plan with the library, has one,
     * and has not switched the marks off.
     */
    public boolean on(PartySettingsEntity party) {
        return party.isActive() && party.isLibraryHints() && planService.allows(party, PlanFeature.LIBRARY)
                && libraryService.info(party.getPartyCode()).isPresent();
    }

    /**
     * For each of the songs ("Artist - Title", as the suggestions name them), whether the party's library has it ("✓": the title and
     * an artist in common) — one lookup for all of them. At most {@value #MOST_SONGS} are looked at, each cut to
     * {@value #LONGEST_SONG} characters; the answer is as long as what was looked at. The caller checks {@link #on} first.
     */
    public List<Boolean> have(String partyCode, List<String> songs) {
        List<LibraryKey> keys = songs.stream().limit(MOST_SONGS)
                .map(song -> LibraryKey.of(song.length() > LONGEST_SONG ? song.substring(0, LONGEST_SONG) : song)).toList();
        if (keys.isEmpty()) {
            return List.of();
        }
        Map<LibraryKey, LibraryMark> marks = libraryService.marks(partyCode, new HashSet<>(keys));
        return keys.stream().map(key -> marks.get(key) == LibraryMark.HAVE).toList();
    }
}
