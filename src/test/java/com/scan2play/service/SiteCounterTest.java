package com.scan2play.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The site's counter (V40): people, not robots; a count that fails never fails the page. The SQL itself: SiteCounterIT. */
class SiteCounterTest {

    @Test
    void aPersonsBrowser_isCounted_aRobotsIsNot() {
        assertThat(SiteCounter.isPerson("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/156.0 Safari/537.36")).isTrue();
        assertThat(SiteCounter.isPerson("Mozilla/5.0 (iPhone; CPU iPhone OS 27_0_1 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148")).isTrue();
        assertThat(SiteCounter.isPerson("Mozilla/5.0 (Linux; Android 16; SM-S931B) AppleWebKit/537.36 Chrome/156.0 Mobile Safari/537.36")).isTrue();

        // seen in Railway's log, 2026-10-22 … 23: Facebook's previews of a post, AI and search crawlers, scanners, curl
        assertThat(SiteCounter.isPerson("facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")).isFalse();
        assertThat(SiteCounter.isPerson("Mozilla/5.0 (compatible; Meta-ExternalAgent/1.0; +https://developers.facebook.com/docs/sharing/webmasters/crawler)")).isFalse();
        assertThat(SiteCounter.isPerson("Mozilla/5.0 (compatible; GrokBot/1.0; +https://x.ai/)")).isFalse();
        assertThat(SiteCounter.isPerson("Googlebot-Image/1.0")).isFalse();
        assertThat(SiteCounter.isPerson("Hello from Palo Alto Networks, find out more about our scans in https://docs-cortex.paloaltonetworks.com/")).isFalse();
        assertThat(SiteCounter.isPerson("Mozilla/5.0 (compatible; CMS-Checker/1.0; +https://example.com)")).isFalse();
        assertThat(SiteCounter.isPerson("curl/8.7.1")).isFalse();
        assertThat(SiteCounter.isPerson("")).as("no name at all").isFalse();
        assertThat(SiteCounter.isPerson(null)).isFalse();
    }

    @Test
    void aRobot_isNotCounted() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new SiteCounter(jdbc).countVisit(SiteCounter.Event.LANDING_PL, "facebookexternalhit/1.1");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void aCountThatFails_isOnlyALineInTheLog() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new DataAccessResourceFailureException("the database is away"));
        assertThatCode(() -> new SiteCounter(jdbc).count(SiteCounter.Event.START)).doesNotThrowAnyException();
    }
}
