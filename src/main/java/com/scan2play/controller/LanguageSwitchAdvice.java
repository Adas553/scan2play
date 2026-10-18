package com.scan2play.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The switch "Polski · English" in every page's footer ({@code fragments/components :: footer}; the owner, 2026-10-18): the page's
 * own address with {@code ?lang=pl} or {@code ?lang=en} — WebConfig shows the page in that language and keeps the pick in its cookie
 * for the next pages —, the address's other parameters kept (the panel's {@code ?party=}). None for a page answered to a POST (the
 * result of a guest's request): its address is not a page to open again.
 */
@ControllerAdvice
public class LanguageSwitchAdvice {

    /** The two links of the switch: this page in Polish, and in English. */
    public record LanguageSwitch(String pl, String en) {
    }

    @ModelAttribute("langSwitch")
    public LanguageSwitch langSwitch(HttpServletRequest request) {
        String path = request.getRequestURI();
        // only the site's own pages: a path that starts "//" would be a link to another host (the firewall refuses one anyway)
        if (!"GET".equals(request.getMethod()) || path == null || !path.startsWith("/") || path.startsWith("//")) {
            return null;
        }
        return new LanguageSwitch(withLang(path, request.getQueryString(), "pl"), withLang(path, request.getQueryString(), "en"));
    }

    private static String withLang(String path, String query, String lang) {
        return UriComponentsBuilder.fromPath(path).query(query).replaceQueryParam(LANG_PARAM, lang).build().toUriString();
    }

    /** The parameter WebConfig reads ({@code WebConfig.LANG_PARAM}, package-private there). */
    private static final String LANG_PARAM = "lang";
}
