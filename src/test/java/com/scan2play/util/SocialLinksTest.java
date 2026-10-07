package com.scan2play.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocialLinksTest {

    @ParameterizedTest(name = "\"{0}\"")
    @DisplayName("Instagram: \"@name\", a name, or a link from the app — one address")
    @ValueSource(strings = {"@dj.koko", "dj.koko", " dj.koko ", "instagram.com/dj.koko", "https://www.instagram.com/dj.koko/",
            "http://instagram.com/dj.koko", "https://www.instagram.com/dj.koko?igsh=MWx0eGZ3", "https://m.instagram.com/dj.koko/reels/"})
    void instagram(String typed) {
        assertThat(SocialLinks.instagram(typed)).isEqualTo("https://www.instagram.com/dj.koko/");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @DisplayName("TikTok: \"@name\", a name, or a link from the app — one address")
    @ValueSource(strings = {"@dj_koko", "dj_koko", "tiktok.com/@dj_koko", "https://www.tiktok.com/@dj_koko?_t=8abc&_r=1",
            "https://www.tiktok.com/@dj_koko/video/7312345"})
    void tiktok(String typed) {
        assertThat(SocialLinks.tiktok(typed)).isEqualTo("https://www.tiktok.com/@dj_koko");
    }

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @DisplayName("Facebook: a page's name, its link, or the forms of a page without a name")
    @CsvSource({
            "djkoko, https://www.facebook.com/djkoko",
            "@dj.koko, https://www.facebook.com/dj.koko",
            "https://www.facebook.com/djkoko/, https://www.facebook.com/djkoko",
            "fb.com/djkoko, https://www.facebook.com/djkoko",
            "https://m.facebook.com/djkoko?ref=share, https://www.facebook.com/djkoko",
            "https://www.facebook.com/profile.php?id=100012345678, https://www.facebook.com/profile.php?id=100012345678",
            "https://www.facebook.com/people/DJ-Koko/100012345678/, https://www.facebook.com/people/DJ-Koko/100012345678/",
    })
    void facebook(String typed, String address) {
        assertThat(SocialLinks.facebook(typed)).isEqualTo(address);
    }

    @Test
    @DisplayName("nothing typed clears the profile")
    void nothingTyped_isNull() {
        assertThat(SocialLinks.instagram("  ")).isNull();
        assertThat(SocialLinks.facebook(null)).isNull();
        assertThat(SocialLinks.tiktok("")).isNull();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @DisplayName("an address on another site, a look-alike host, a post instead of a profile, a script: refused")
    @ValueSource(strings = {"https://evil.example/dj.koko", "https://instagram.com.evil.example/dj.koko", "evil.example/instagram.com/x",
            "javascript:alert(1)", "https://www.instagram.com/p/C1abc/", "dj koko", "https://www.instagram.com/"})
    void notAnInstagramProfile_isRefused(String typed) {
        assertThatThrownBy(() -> SocialLinks.instagram(typed)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"https://www.facebook.com/profile.php", "https://facebook.com.evil.example/djkoko", "data:text/html,x",
            "<b>dj</b>", "https://www.tiktok.com/@djkoko"})
    void notAFacebookPage_isRefused(String typed) {
        assertThatThrownBy(() -> SocialLinks.facebook(typed)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("on paper: \"@name\" for Instagram and TikTok, the page's name for Facebook — none for a page known by its number")
    void handle() {
        assertThat(SocialLinks.handle("https://www.instagram.com/dj.koko/")).isEqualTo("@dj.koko");
        assertThat(SocialLinks.handle("https://www.tiktok.com/@dj_koko")).isEqualTo("@dj_koko");
        assertThat(SocialLinks.handle("https://www.facebook.com/djkoko")).isEqualTo("djkoko");
        assertThat(SocialLinks.handle("https://www.facebook.com/profile.php?id=100012345678")).isNull();
        assertThat(SocialLinks.handle("https://www.facebook.com/people/DJ-Koko/100012345678/")).isNull();
        assertThat(SocialLinks.handle(null)).isNull();
    }
}
