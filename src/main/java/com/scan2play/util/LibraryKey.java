package com.scan2play.util;

import com.scan2play.model.LibraryMark;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A song of the DJ's library as the queue matches it (V42): its {@code title} and its {@code artists}, each
 * {@link SongNames#comparable} — the case, the accents and the punctuation never tell two songs apart:
 * <ul>
 *     <li>the title without a version: in brackets ("(Radio Edit)", "[Extended Mix]", "(feat. X)") or after a dash with a version's
 *     word ("- Original Mix", "- 2011 Remaster", "- Live"), and without a guest artist ("feat. X");</li>
 *     <li>the artists a set, sorted and joined by "+": split at ",", "&amp;", "x", "and", "i", "feat.", "vs" …, each without a leading
 *     "The" — "Kygo &amp; Selena Gomez" is "Selena Gomez, Kygo".</li>
 * </ul>
 * A request is "✓" when the library has its title with an artist in common, "? Podobny" with the title alone ({@link #markIn}).
 * Nothing but these keys is kept of the DJ's file: no title as written, no path.
 */
public record LibraryKey(String title, String artists) {

    /** The longest key: as the columns {@code dj_library_track.title_key} and {@code artists}. */
    public static final int MAX = 200;

    /** A title shorter than this ("Hej", "Sen") is too common to call another artist's song of that name "similar". */
    static final int SHORTEST_TITLE = 4;

    /** Between the artists of a song. */
    private static final Pattern ARTISTS = Pattern.compile(
            "(?i)\\s*(?:,|;|&|\\+|/|\\s(?:x|and|i|oraz|feat\\.?|ft\\.?|featuring|vs\\.?|with)\\s)\\s*");

    private static final Pattern THE = Pattern.compile("(?i)^the\\s+");

    /** A version in brackets: "(Radio Edit)", "[Extended Mix]", "(feat. Rihanna)". */
    private static final Pattern BRACKETS = Pattern.compile("\\([^)]*\\)|\\[[^]]*]");

    /** A guest artist in a title, up to the dash or the end: "feat. Rihanna", "ft. X", "featuring Y". */
    private static final Pattern FEATURING = Pattern.compile("(?i)\\s(?:feat\\.?|ft\\.?|featuring)\\s[^-]*");

    /** The last part after a dash when it names a version: "Original Mix", "2011 Remaster", "Live". */
    private static final Pattern VERSION_AFTER_DASH = Pattern.compile(
            "(?i)\\s-\\s[^-]*\\b(?:mix|edit|remix|version|remaster(?:ed)?|radio|extended|original|club|live|acoustic|instrumental"
                    + "|rework|bootleg|vip|dub)\\b[^-]*$");

    /** The AI's one line, "Artist - Title" (the first dash parts them; none: a title alone). */
    public static LibraryKey of(String name) {
        String line = SongNames.tidy(name);
        int dash = line.indexOf(" - ");
        return dash < 0 ? of("", line) : of(line.substring(0, dash), line.substring(dash + 3));
    }

    /** A track of a collection, by its artist and title as the program keeps them. */
    public static LibraryKey of(String artist, String title) {
        String artists = Arrays.stream(ARTISTS.split(SongNames.tidy(artist)))
                .map(one -> SongNames.comparable(THE.matcher(one.strip()).replaceFirst("")))
                .filter(one -> !one.isEmpty()).distinct().sorted().collect(Collectors.joining("+"));
        String core = BRACKETS.matcher(SongNames.tidy(title)).replaceAll(" ");
        core = VERSION_AFTER_DASH.matcher(FEATURING.matcher(" " + core).replaceAll(" ").strip()).replaceAll("");
        return new LibraryKey(cut(SongNames.comparable(core)), cut(artists));
    }

    /** No title left to match: a line of punctuation, a version alone. */
    public boolean isEmpty() {
        return title.isEmpty();
    }

    /**
     * This request against the library's tracks of its title: "✓" when one of them has an artist in common, "? Podobny" when none
     * has (and the title is long enough to say so), "✗" when there is no track of the title.
     */
    public LibraryMark markIn(Collection<LibraryKey> library) {
        return markIn(library, Set.of());
    }

    /**
     * The same with the {@code swapped} keys of an M3U's names read the other way round ({@code DjLibraryParser.Library}): one of them
     * with an artist in common is "✓" too, but the same title alone is never "? Podobny" — its title is an artist's name, and "Ed
     * Sheeran - Perfect" is no song like the band Perfect's.
     */
    public LibraryMark markIn(Collection<LibraryKey> library, Collection<LibraryKey> swapped) {
        if (isEmpty()) {
            return LibraryMark.MISSING;
        }
        Set<String> mine = artists.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(artists.split("\\+")));
        boolean sameTitle = false;
        for (LibraryKey track : library) {
            if (title.equals(track.title)) {
                sameTitle = true;
                if (track.sharesAnArtistWith(mine)) {
                    return LibraryMark.HAVE;
                }
            }
        }
        for (LibraryKey track : swapped) {
            if (title.equals(track.title) && track.sharesAnArtistWith(mine)) {
                return LibraryMark.HAVE;
            }
        }
        return sameTitle && title.length() >= SHORTEST_TITLE ? LibraryMark.SIMILAR : LibraryMark.MISSING;
    }

    private boolean sharesAnArtistWith(Set<String> theirs) {
        return !artists.isEmpty() && Arrays.stream(artists.split("\\+")).anyMatch(theirs::contains);
    }

    private static String cut(String key) {
        return key.length() > MAX ? key.substring(0, MAX) : key;
    }
}
