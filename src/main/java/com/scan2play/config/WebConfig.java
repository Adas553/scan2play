package com.scan2play.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Locale;

/**
 * The language of a page (the search engines, 2026-10-14): {@code ?lang=pl} / {@code ?lang=en} names it — one address per language,
 * which the pages' {@code hreflang} links and the sitemap give —, else the browser's {@code Accept-Language} as before, and with
 * none (a search engine's crawler sends none) Polish: Scan2Play is a Polish product, and Google indexed the English page before.
 * Nothing is remembered: the parameter holds for the one request.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    static final String LANG_PARAM = "lang";
    static final Locale POLISH = Locale.forLanguageTag("pl");

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver byHeader = new AcceptHeaderLocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                String lang = request.getParameter(LANG_PARAM);
                if ("pl".equals(lang)) {
                    return POLISH;
                }
                if ("en".equals(lang)) {
                    return Locale.ENGLISH;
                }
                return super.resolveLocale(request);
            }
        };
        byHeader.setDefaultLocale(POLISH);
        return byHeader;
    }
}
