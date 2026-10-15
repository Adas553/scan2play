package com.scan2play.controller;

import com.scan2play.service.Plans;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sitemap and the plans (V35): "Scan2Play Pro" is a page of the sitemap only while the plans are enforced — before, {@code /pro}
 * is a 404 and must not be offered to a crawler ({@code SmokeTest} covers the plans off).
 */
class SeoControllerTest {

    @Test
    void theSitemap_listsPro_onlyWhileThePlansAreEnforced() {
        String enforced = new SeoController("https://www.scan2play.com.pl/", Plans.enforced()).sitemap().getBody();
        String off = new SeoController("https://www.scan2play.com.pl", Plans.off()).sitemap().getBody();

        assertThat(enforced).contains("<loc>https://www.scan2play.com.pl/pro</loc>", "<loc>https://www.scan2play.com.pl/pro?lang=en</loc>");
        assertThat(off).doesNotContain("/pro<", "/pro?");
    }
}
