package com.scan2play;

import com.scan2play.config.SecurityConfig;
import com.scan2play.controller.HomeController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies that English visitors get English text even when the server JVM runs with
 * a Polish default locale. Without messages_en.properties, Spring falls back to the
 * system locale (pl) before the default bundle, so English requests rendered Polish.
 */
@WebMvcTest(HomeController.class)
@Import(SecurityConfig.class)
class EnglishLocaleTest {

    private static Locale originalDefault;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void usePolishSystemLocale() {
        originalDefault = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("pl-PL"));
    }

    @AfterAll
    static void restoreSystemLocale() {
        Locale.setDefault(originalDefault);
    }

    @Test
    @DisplayName("Accept-Language: en renders the landing page in English")
    void english_shouldRenderEnglish() throws Exception {
        mockMvc.perform(get("/").header("Accept-Language", "en-US,en;q=0.9"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("How does it work?")));
    }

    @Test
    @DisplayName("Accept-Language: pl still renders the landing page in Polish")
    void polish_shouldRenderPolish() throws Exception {
        mockMvc.perform(get("/").header("Accept-Language", "pl-PL"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Jak to działa?")));
    }
}
