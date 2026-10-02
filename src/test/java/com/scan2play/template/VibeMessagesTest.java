package com.scan2play.template;

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
            assertThat(messages.getMessage("vibe.ANY.requests", null, null, locale)).isNotBlank();
        }
        assertThat(messages.getMessage("vibe.LATINO", null, Locale.forLanguageTag("pl"))).contains("salsa", "bachata", "reggaeton");
    }
}
