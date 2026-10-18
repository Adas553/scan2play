package com.scan2play.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The switch "Polski · English" in every page's footer (the owner, 2026-10-18): the page's own address with {@code ?lang=pl} or
 * {@code ?lang=en} — which WebConfig keeps in the cookie for the next pages —, its other parameters kept (the panel's
 * {@code ?party=}). None for a page answered to a POST (the result of a request): its address is not a page to open again.
 */
class LanguageSwitchAdviceTest {

    private final LanguageSwitchAdvice advice = new LanguageSwitchAdvice();

    private static MockHttpServletRequest get(String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setQueryString(query);
        return request;
    }

    @Test
    void thePagesOwnAddress_withEachLanguage() {
        assertThat(advice.langSwitch(get("/", null)))
                .isEqualTo(new LanguageSwitchAdvice.LanguageSwitch("/?lang=pl", "/?lang=en"));
        assertThat(advice.langSwitch(get("/p/ABC12", null)).en()).isEqualTo("/p/ABC12?lang=en");
    }

    @Test
    void theOtherParameters_areKept_andAnEarlierLangReplaced() {
        assertThat(advice.langSwitch(get("/dj/dashboard", "party=AB1&lang=pl")))
                .isEqualTo(new LanguageSwitchAdvice.LanguageSwitch("/dj/dashboard?party=AB1&lang=pl", "/dj/dashboard?party=AB1&lang=en"));
        assertThat(advice.langSwitch(get("/dj/history-view", "filter=played&limit=100")).en())
                .isEqualTo("/dj/history-view?filter=played&limit=100&lang=en");
    }

    @Test
    void noSwitch_forAPageAnsweredToAPost_orAnAddressOfAnotherHost() {
        assertThat(advice.langSwitch(new MockHttpServletRequest("POST", "/p/ABC12/request"))).isNull();
        assertThat(advice.langSwitch(get("//evil.example/x", null))).as("never a link to another host").isNull();
    }
}
