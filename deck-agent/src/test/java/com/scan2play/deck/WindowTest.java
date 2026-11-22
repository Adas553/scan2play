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
        for (String text : java.util.List.of(Window.line(nothingRead).text(), Window.NO_HISTORY, Window.NOT_IN_FOLDER)) {
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
}
