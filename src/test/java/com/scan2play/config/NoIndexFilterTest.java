package com.scan2play.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** A party's pages, an invitation and the DJ's own pages say "noindex" to a search engine; the public pages do not (2026-10-14). */
class NoIndexFilterTest {

    private static String robotsOf(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new NoIndexFilter().doFilter(new MockHttpServletRequest("GET", path), response, new MockFilterChain());
        return response.getHeader("X-Robots-Tag");
    }

    @Test
    void thePagesOfOneParty_orOnePerson_areNotIndexed() throws Exception {
        for (String path : new String[]{"/p/ABC12", "/p/ABC12/queue/more", "/h/token", "/k", "/k/token", "/join", "/join/token", "/dj/dashboard", "/dj/settings"}) {
            assertThat(robotsOf(path)).as(path).isEqualTo("noindex, nofollow");
        }
    }

    @Test
    void thePublicPages_are() throws Exception {
        for (String path : new String[]{"/", "/jak-to-dziala", "/how-it-works", "/privacy", "/terms", "/pro", "/robots.txt", "/sitemap.xml",
                "/images/og-image.png", "/pricing", "/hosts", "/kluby"}) {
            assertThat(robotsOf(path)).as(path).isNull();
        }
    }
}
