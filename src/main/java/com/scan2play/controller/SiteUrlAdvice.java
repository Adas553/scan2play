package com.scan2play.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * The site's own address for the public pages' head ({@code fragments/components :: seo}): the canonical address, the other
 * language's, the picture of a shared link — a search engine and a messenger need them whole, with the host (2026-10-14).
 */
@ControllerAdvice(assignableTypes = {HomeController.class, LegalController.class})
public class SiteUrlAdvice {

    private final String siteUrl;

    public SiteUrlAdvice(@Value("${scan2play.site-url}") String siteUrl) {
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    @ModelAttribute("siteUrl")
    public String siteUrl() {
        return siteUrl;
    }
}
