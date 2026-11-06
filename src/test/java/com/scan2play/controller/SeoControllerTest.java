package com.scan2play.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sitemap and the pricing page: {@code /pro} is a page of the sitemap whether the plans are enforced or not — before, it says
 * that everything is free ({@code LegalController.pro}).
 */
class SeoControllerTest {

    @Test
    void theSitemap_listsThePricingPage_inBothLanguages() {
        String sitemap = new SeoController("https://www.scan2play.com.pl/").sitemap().getBody();

        assertThat(sitemap).contains("<loc>https://www.scan2play.com.pl/pro</loc>", "<loc>https://www.scan2play.com.pl/pro?lang=en</loc>");
    }
}
