package com.scan2play.template;

import com.scan2play.model.CommentStyle;
import com.scan2play.model.VibeType;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Every vibe of the list has its name in both languages — a missing one shows the guest "??vibe.X_pl??". */
class VibeMessagesTest {

    @Test
    void everyVibe_hasItsNameInPolishAndEnglish() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        messages.setUseCodeAsDefaultMessage(false);

        for (Locale locale : new Locale[]{Locale.forLanguageTag("pl"), Locale.ENGLISH}) {
            for (VibeType vibe : VibeType.values()) {
                assertThat(messages.getMessage("vibe." + vibe.name(), null, null, locale)).as(vibe + " in " + locale).isNotBlank();
            }
        }
        // "Any": no genre, the AI judges (the guests pick no vibe)
        assertThat(messages.getMessage("vibe.ANY", null, Locale.ENGLISH)).isEqualTo("Any (the AI judges)");
        assertThat(messages.getMessage("vibe.ANY", null, Locale.forLanguageTag("pl"))).isEqualTo("Dowolny (ocenia AI)");
        assertThat(messages.getMessage("vibe.LATINO", null, Locale.forLanguageTag("pl"))).contains("salsa", "bachata", "reggaeton");
    }

    /** Every comment style (V22) has its name in both languages — the Polish one of its own, not the English. */
    @Test
    void everyCommentStyle_hasItsNameInPolishAndEnglish() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);

        Locale pl = Locale.forLanguageTag("pl");
        for (CommentStyle style : CommentStyle.values()) {
            String key = "comment.style." + style.name();
            String english = messages.getMessage(key, null, null, Locale.ENGLISH);
            String polish = messages.getMessage(key, null, null, pl);
            assertThat(english).as(key + " en").isNotBlank();
            assertThat(polish).as(key + " pl").isNotBlank().isNotEqualTo(english);
        }
        assertThat(messages.getMessage("comment.style.SARCASTIC_LIGHT", null, pl)).isEqualTo("Sarkastyczne (łagodne)");
    }
}
