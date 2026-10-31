package com.scan2play.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * The pages of one party or one person are not for a search engine (2026-10-14): a party's guest page ({@code /p/…}), the hosts' page
 * ({@code /h/…}), the venue's kiosk ({@code /k/…}, V56), an invitation ({@code /join…}) and the DJ's own pages say {@code X-Robots-Tag: noindex, nofollow} — a link to a
 * party put on the web must not make it a search result. A header, not {@code robots.txt}: a crawler that may not fetch a page never
 * sees its "noindex" and can still list its address.
 */
@Component
public class NoIndexFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Robots-Tag";
    static final String NO_INDEX = "noindex, nofollow";
    private static final List<String> PRIVATE = List.of("/p/", "/h/", "/k/", "/join", "/dj/");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // "/k" alone: the kiosk's page by its cookie (a prefix would take "/kluby" too)
        if ("/k".equals(path) || PRIVATE.stream().anyMatch(path::startsWith)) {
            response.setHeader(HEADER, NO_INDEX);
        }
        chain.doFilter(request, response);
    }
}
