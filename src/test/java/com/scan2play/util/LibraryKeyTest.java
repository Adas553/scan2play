package com.scan2play.util;

import com.scan2play.model.LibraryMark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A song of the DJ's library as the queue matches it (V42): the AI's "Artist - Title" against the DJ's file. The same title and an
 * artist in common is "✓" — whatever the order of the artists, a "The", a guest artist, a version in brackets or after a dash; the
 * same title without one is "? Podobny"; another title "✗".
 */
class LibraryKeyTest {

    private static LibraryMark mark(String asked, String... library) {
        return LibraryKey.of(asked).markIn(java.util.Arrays.stream(library).map(LibraryKey::of).toList());
    }

    @ParameterizedTest(name = "\"{0}\" is \"{1}\"")
    @DisplayName("the same song: ✓")
    @CsvSource(delimiter = '|', value = {
            "Daft Punk - One More Time                       | Daft Punk - One More Time (Radio Edit)",
            "Daft Punk - One More Time                       | DAFT PUNK – one more time [Extended Mix]",
            "Wilki - Baśka                                   | Wilki - Baska",
            "Calvin Harris - This Is What You Came For       | Calvin Harris feat. Rihanna - This Is What You Came For",
            "Calvin Harris - This Is What You Came For       | Calvin Harris - This Is What You Came For (feat. Rihanna)",
            "Kygo & Selena Gomez - It Ain't Me               | Selena Gomez, Kygo - It Ain't Me",
            "The Killers - Mr. Brightside                    | Killers - Mr Brightside",
            "Kayah i Bregović - Prawy do lewego              | Bregović & Kayah - Prawy do lewego",
            "David Guetta x Sia - Titanium                   | David Guetta feat. Sia - Titanium",
            "Avicii - Levels                                 | Avicii - Levels - Original Mix",
            "Queen - Don't Stop Me Now                       | Queen - Don't Stop Me Now - 2011 Remaster",
            "Lady Pank - Mniej niż zero - Live               | Lady Pank - Mniej niż zero",
    })
    void theSameSong(String asked, String inLibrary) {
        assertThat(mark(asked, inLibrary)).isEqualTo(LibraryMark.HAVE);
    }

    @Test
    @DisplayName("the artist and the title of a rekordbox track are the AI's one line")
    void theArtistAndTitle_asOneLine() {
        assertThat(LibraryKey.of("Selena Gomez, Kygo", "It Ain't Me (Original Mix)")).isEqualTo(LibraryKey.of("Kygo & Selena Gomez - It Ain't Me"));
        assertThat(LibraryKey.of("", "Intro")).isEqualTo(LibraryKey.of("Intro"));
    }

    @Test
    @DisplayName("the same title, no artist in common (or none known): \"? Podobny\"")
    void theSameTitle_anotherArtist_isSimilar() {
        assertThat(mark("Wilki - Baśka", "Dżem - Baśka")).isEqualTo(LibraryMark.SIMILAR);
        assertThat(mark("Baśka", "Wilki - Baśka")).as("the AI named no artist").isEqualTo(LibraryMark.SIMILAR);
        assertThat(mark("Wilki - Baśka", "Baśka")).as("the file names none").isEqualTo(LibraryMark.SIMILAR);
        assertThat(mark("Wilki - Baśka", "Dżem - Baśka", "Wilki - Baśka (Live)")).as("one of them is the song").isEqualTo(LibraryMark.HAVE);
    }

    @Test
    @DisplayName("another title: ✗; a title too short to tell songs apart is never \"similar\"")
    void anotherTitle_isMissing() {
        assertThat(mark("Daft Punk - One More Time", "Daft Punk - Digital Love")).isEqualTo(LibraryMark.MISSING);
        assertThat(mark("Artist - Part One - Part Two", "Artist - Part One")).as("a dash that names no version stays").isEqualTo(LibraryMark.MISSING);
        assertThat(mark("Kult - Hej", "Golec uOrkiestra - Hej")).as("\"hej\": too short to say \"similar\"").isEqualTo(LibraryMark.MISSING);
        assertThat(mark("Kult - Hej", "Kult - Hej")).isEqualTo(LibraryMark.HAVE);
        assertThat(mark("Wilki - Baśka")).isEqualTo(LibraryMark.MISSING);
    }

    @Test
    @DisplayName("an M3U's name read the other way round: \"✓\" with an artist in common, never \"? Podobny\" by its title alone")
    void aSwappedKey_isHaveOrNothing() {
        List<LibraryKey> none = List.of();
        List<LibraryKey> swapped = List.of(LibraryKey.of("Autobiografia - Perfect"), LibraryKey.of("Bohemian Rhapsody - Queen"));
        assertThat(LibraryKey.of("Ed Sheeran - Perfect").markIn(none, swapped)).as("the band's name is no song of that title")
                .isEqualTo(LibraryMark.MISSING);
        assertThat(LibraryKey.of("Jessie J - Queen").markIn(none, swapped)).isEqualTo(LibraryMark.MISSING);
        assertThat(LibraryKey.of("Autobiografia - Perfect").markIn(none, swapped)).as("the AI wrote it the other way round too")
                .isEqualTo(LibraryMark.HAVE);
        assertThat(LibraryKey.of("Ed Sheeran - Perfect").markIn(List.of(LibraryKey.of("Ed Sheeran - Perfect (Acoustic)")), swapped))
                .isEqualTo(LibraryMark.HAVE);
    }

    @Test
    @DisplayName("nothing to compare is empty; a long name is cut to the columns")
    void emptyAndLong() {
        assertThat(LibraryKey.of(null).isEmpty()).isTrue();
        assertThat(LibraryKey.of(" (Radio Edit) - !!").isEmpty()).isTrue();
        LibraryKey longOne = LibraryKey.of("a".repeat(500) + " - " + "b".repeat(500));
        assertThat(longOne.title()).hasSize(LibraryKey.MAX);
        assertThat(longOne.artists()).hasSize(LibraryKey.MAX);
    }
}
