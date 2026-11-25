package com.scan2play.deck;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The window's own answers, before anything goes to the server. */
class WindowTest {

    @Test
    void anEmptyCode_asksForTheCode_insteadOfSayingItDoesNotWork() {
        assertEquals("Wpisz kod z panelu.", Window.beforeSending(""));
        assertEquals("Wpisz kod z panelu.", Window.beforeSending("   "));
        assertNull(Window.beforeSending("123456"), "a code typed goes to the server");
    }

    /**
     * A song's name as long as a guest's request may be (a remix's whole title) would widen the window past the screen, "Odłącz ten
     * komputer" and the time with it: cut, with "…", the whole name in its tooltip. A short one as it is.
     */
    @Test
    void aLongSongName_isCut_soTheWindowStaysOnTheScreen() {
        String long_ = "Zespół Pieśni i Tańca Mazowsze feat. Chór Filharmonii Narodowej - Cyganeczka Zosia (wersja rozszerzona, extended mix)";
        String cut = Window.shortened(long_);
        assertEquals(Window.LONGEST_LINE, cut.length());
        assertEquals(long_.substring(0, Window.LONGEST_LINE - 1) + "…", cut);
        assertEquals("Sanah - Szampan", Window.shortened("Sanah - Szampan"));
        assertEquals("a".repeat(Window.LONGEST_LINE), Window.shortened("a".repeat(Window.LONGEST_LINE)), "exactly as long: whole");
    }

    /** The texts speak without a person, as the app's (no "nie widzę", "czekam"): the program is not someone. */
    @Test
    void theTexts_areImpersonal() {
        Agent.Status nothingRead = new Agent.Status(true, java.util.List.of(), java.time.Instant.EPOCH, java.util.List.of(), 0,
                ServerClient.Outcome.OK);
        for (String text : java.util.List.of(Window.line(nothingRead).text(), Window.NO_HISTORY, Window.NOT_IN_FOLDER,
                Window.OTHER_FOLDER, Window.OTHER_FOLDER_TIP, Window.SPOTIFY_UNSEEN_TIP, SpotifyNowPlaying.NOTHING,
                SpotifyNowPlaying.HIDDEN, SpotifyNowPlaying.NO_CONSENT)) {
            assertNull(java.util.regex.Pattern.compile("(?iU)\\b(widzę|czekam|mogę|szukam)\\b").matcher(text).results().findFirst()
                    .map(java.util.regex.MatchResult::group).orElse(null), text);
        }
    }

    /**
     * Java has no Polish texts of its own dialogs: the folder chooser ("Wskaż folder historii…") said "Look In", "Folder name",
     * "Cancel" beside the program's "Wybierz", on a Polish Windows too. Its texts are given in Polish.
     */
    @Test
    void theFolderChooser_speaksPolish() {
        Window.polishDialogs();
        assertEquals("Szukaj w:", javax.swing.UIManager.getString("FileChooser.lookInLabelText"));
        assertEquals("Nazwa folderu:", javax.swing.UIManager.getString("FileChooser.folderNameLabelText"));
        assertEquals("Anuluj", javax.swing.UIManager.getString("FileChooser.cancelButtonText"));
        assertEquals("Anuluj", javax.swing.UIManager.getString("OptionPane.cancelButtonText"));
    }

    /**
     * The window is packed to its content, and the status line's width differs by state (no server, no Pro): with a venue's Wi-Fi
     * coming and going the window would change its width every few seconds. It grows when needed and never narrows; its height
     * follows the content (the rows of the songs).
     */
    @Test
    void theWindow_growsButNeverNarrows() {
        java.awt.Dimension before = new java.awt.Dimension(620, 365);
        assertEquals(new java.awt.Dimension(620, 419), Window.grownOnly(new java.awt.Dimension(492, 419), before));
        assertEquals(new java.awt.Dimension(700, 300), Window.grownOnly(new java.awt.Dimension(700, 300), before));
        assertEquals(new java.awt.Dimension(492, 419), Window.grownOnly(new java.awt.Dimension(492, 419), null), "the first pack");
    }

    /** A Spotify song's whole title in "CZYTA" would widen the window for good: cut as a song is, the whole in the tooltip. */
    @Test
    void aLongSpotifyTitleInReading_isCut() {
        String source = "Spotify (gra: Queen, David Bowie - Under Pressure - Remastered 2011 / Live at Wembley Stadium 1986)";
        javax.swing.JLabel words = (javax.swing.JLabel) Window.readingRow(source).getComponent(2);
        assertEquals(Window.shortened(source), words.getText());
        assertEquals(source, words.getToolTipText());
    }

    /** Spotify silent for a reason: "!" in amber and what to do in the tooltip, not a green "✓". */
    @Test
    void spotifyThatHearsNothing_isMarked_andSaysWhatToDo() {
        javax.swing.JPanel row = Window.readingRow("Spotify (" + SpotifyNowPlaying.HIDDEN + ")");
        assertEquals("!", ((javax.swing.JLabel) row.getComponent(0)).getText());
        assertEquals(Window.SPOTIFY_UNSEEN_TIP, ((javax.swing.JLabel) row.getComponent(2)).getToolTipText());
        assertEquals("✓", ((javax.swing.JLabel) Window.readingRow("Spotify (nic nie gra)").getComponent(0)).getText());
    }

    /** Before the first song, the window says when one marks itself — Serato's few seconds and VirtualDJ's 45. */
    @Test
    void nothingMarkedYet_speaksOfBothPrograms() {
        assertEquals(true, Window.NOTHING_MARKED.contains("VirtualDJ") && Window.NOTHING_MARKED.contains("Serato"), Window.NOTHING_MARKED);
    }
}
