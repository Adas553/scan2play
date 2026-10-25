package com.scan2play.template;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The review of 2026-10-21, U2: half the guests of a wedding are women, and a DJ is not always a man — the Polish texts speak to the
 * person without a gender ("Limit … wykorzystany", not "Wykorzystałeś limit"). A masculine "you did" ("-łeś") or "Gotowy" in the
 * bundle is a text to rewrite, not to keep.
 */
class ImpersonalPolishTextsTest {

    /**
     * "-łeś" / "-łaś" (you did, by gender), "-łbyś" / "-łabyś" (you would) and "gotowy" / "gotowa" (ready, by gender), as whole words,
     * in any case.
     */
    private static final Pattern GENDERED = Pattern.compile("\\p{L}+(łeś|łaś|łbyś|łabyś)\\b|\\bgotow[ya]\\b",
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE);

    private static Properties polish() throws IOException {
        Properties texts = new Properties();
        try (InputStream in = ImpersonalPolishTextsTest.class.getResourceAsStream("/messages_pl.properties")) {
            texts.load(in);   // the \\uXXXX escapes of the file decoded
        }
        return texts;
    }

    @Test
    void noPolishText_speaksToTheGuestOrTheDjByGender() throws IOException {
        Properties texts = polish();
        assertThat(texts.stringPropertyNames()).as("the bundle was read").hasSizeGreaterThan(100);
        assertThat(texts.stringPropertyNames()).allSatisfy(key ->
                assertThat(GENDERED.matcher(texts.getProperty(key)).find()).as(key + " = " + texts.getProperty(key)).isFalse());
    }

    /** The owner's words (2026-10-24). */
    @Test
    void theThreeRewrittenTexts_areTheOwners() throws IOException {
        Properties texts = polish();
        assertThat(texts.getProperty("guest.error.rate_limit")).isEqualTo("Limit {0} piosenek wykorzystany. Daj szansę innym i spróbuj za {1}.");
        assertThat(texts.getProperty("dashboard.table.button.mark_played.title")).isEqualTo("Zagrana — prośba przechodzi do historii");
        assertThat(texts.getProperty("landing.final.title")).isEqualTo("Czas na następną imprezę?");
    }
}
