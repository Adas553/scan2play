package com.scan2play.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.time.Duration;
import java.util.Locale;

/**
 * The language of a page (the search engines, 2026-10-14): {@code ?lang=pl} / {@code ?lang=en} names it — one address per language,
 * which the pages' {@code hreflang} links and the sitemap give; any case, a region after it ({@code EN}, {@code en-US}) too —, else
 * the language the person picked so before (the cookie {@value #LANG_COOKIE}, written by {@code ?lang}: the pages' own links do not
 * carry it), else the browser's {@code Accept-Language}, and with none — or an empty one, or {@code *} — Polish: Scan2Play is a
 * Polish product, Google indexed the English page before, and an empty header gave the server's own language (English on Railway;
 * the review of 2026-10-14). A crawler sends no cookie: its pages are the ones their address names.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    static final String LANG_PARAM = "lang";
    static final String LANG_COOKIE = "lang";
    static final Locale POLISH = Locale.forLanguageTag("pl");
    private static final Duration LANG_COOKIE_AGE = Duration.ofDays(365);

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver byHeader = new AcceptHeaderLocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                Locale named = named(request.getParameter(LANG_PARAM));
                if (named != null) {
                    return named;
                }
                Locale picked = named(cookie(request));
                if (picked != null) {
                    return picked;
                }
                String header = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
                if (header == null || header.isBlank() || "*".equals(header.strip())) {
                    return POLISH;
                }
                return super.resolveLocale(request);
            }
        };
        byHeader.setDefaultLocale(POLISH);
        return byHeader;
    }

    /** {@code ?lang} names a language of ours: the person's pick, kept for their next pages. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Locale named = named(request.getParameter(LANG_PARAM));
                if (named != null) {
                    response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(LANG_COOKIE, named.getLanguage())
                            .path("/").maxAge(LANG_COOKIE_AGE).httpOnly(true).secure(request.isSecure()).sameSite("Lax")
                            .build().toString());
                }
                return true;
            }
        });
    }

    /** Polish or English for "pl" / "en" in any case, with or without a region ("en-US"); null for anything else. */
    static Locale named(String lang) {
        if (lang == null) {
            return null;
        }
        String language = lang.strip().toLowerCase(Locale.ROOT);
        int region = language.indexOf('-') >= 0 ? language.indexOf('-') : language.indexOf('_');
        language = region < 0 ? language : language.substring(0, region);
        return switch (language) {
            case "pl" -> POLISH;
            case "en" -> Locale.ENGLISH;
            default -> null;
        };
    }

    private static String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (LANG_COOKIE.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
