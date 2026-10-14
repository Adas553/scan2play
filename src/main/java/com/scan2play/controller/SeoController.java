package com.scan2play.controller;

import com.scan2play.service.PlanService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * What a search engine reads first (2026-10-14): {@code /robots.txt} — the DJ's pages and the login are not for it, and where the
 * sitemap is — and {@code /sitemap.xml}, the public pages in both languages (Polish at the page's own address, English with
 * {@code ?lang=en} — {@code WebConfig}), each with its alternates. "Scan2Play Pro" only while the plans are enforced (a 404 before).
 * Before this both answered with the landing page: Spring Security sends every unknown address to the login page, {@code /}.
 */
@RestController
public class SeoController {

    /** The public pages, at their Polish address. */
    static final List<String> PAGES = List.of("/", "/jak-to-dziala", "/privacy", "/terms");

    private final String siteUrl;
    private final PlanService planService;

    public SeoController(@Value("${scan2play.site-url}") String siteUrl, PlanService planService) {
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        this.planService = planService;
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots() {
        String body = """
                User-agent: *
                Disallow: /dj/
                Disallow: /start
                Disallow: /oauth2/
                Disallow: /login/

                Sitemap: %s/sitemap.xml
                """.formatted(siteUrl);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(body);
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        List<String> pages = new ArrayList<>(PAGES);
        if (planService.isEnforced()) {
            pages.add("/pro");
        }
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">
                """);
        for (String page : pages) {
            String polish = siteUrl + page;
            String english = polish + "?lang=en";
            String alternates = "    <xhtml:link rel=\"alternate\" hreflang=\"pl\" href=\"" + polish + "\"/>\n"
                    + "    <xhtml:link rel=\"alternate\" hreflang=\"en\" href=\"" + english + "\"/>\n"
                    + "    <xhtml:link rel=\"alternate\" hreflang=\"x-default\" href=\"" + polish + "\"/>\n";
            for (String address : List.of(polish, english)) {
                xml.append("  <url>\n    <loc>").append(address).append("</loc>\n").append(alternates).append("  </url>\n");
            }
        }
        xml.append("</urlset>\n");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml.toString());
    }
}
