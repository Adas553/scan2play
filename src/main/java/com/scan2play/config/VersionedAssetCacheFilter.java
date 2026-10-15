package com.scan2play.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The scripts and styles under the deploy's version ({@code /<version>/js/...}, {@code /<version>/css/...};
 * {@code spring.web.resources.chain.strategy.fixed}) may be kept by the browsers and Cloudflare for a year: a deploy changes every
 * address, and Spring serves an address only under the version of the running deploy (an old one is a 404), so what one address
 * gives never changes. Everything else keeps Spring Security's {@code no-store} — it writes its header only when none is set.
 * A file that is not there or fails (a 404, a 500) is not kept: the header is set back to {@code no-store} for any answer but 200, 206
 * and 304 — a missing file cached by Cloudflare for a year would outlive its fix (review 2026-10-14).
 * <p>
 * Only for a commit's version: locally the version is {@code dev} (no {@code RAILWAY_GIT_COMMIT_SHA}), and the files change under
 * it with every edit.
 */
@Component
public class VersionedAssetCacheFilter extends OncePerRequestFilter {

    static final String CACHE_FOR_A_YEAR = "public, max-age=31536000, immutable";
    /** Spring Security's own value, for an answer that is not the file. */
    static final String NOT_KEPT = "no-cache, no-store, max-age=0, must-revalidate";
    private static final String LOCAL_VERSION = "dev";

    private final String jsPrefix;
    private final String cssPrefix;
    private final boolean enabled;

    public VersionedAssetCacheFilter(@Value("${spring.web.resources.chain.strategy.fixed.version:dev}") String version) {
        this.jsPrefix = "/" + version + "/js/";
        this.cssPrefix = "/" + version + "/css/";
        this.enabled = !version.isBlank() && !LOCAL_VERSION.equals(version);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(jsPrefix) && !path.startsWith(cssPrefix);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_FOR_A_YEAR);
        try {
            chain.doFilter(request, new HttpServletResponseWrapper(response) {
                @Override
                public void setStatus(int status) {
                    super.setStatus(status);
                    keepOnlyTheFile(status);
                }

                @Override
                public void sendError(int status) throws IOException {
                    keepOnlyTheFile(status);
                    super.sendError(status);
                }

                @Override
                public void sendError(int status, String message) throws IOException {
                    keepOnlyTheFile(status);
                    super.sendError(status, message);
                }

                private void keepOnlyTheFile(int status) {
                    if (status != HttpServletResponse.SC_OK && status != HttpServletResponse.SC_PARTIAL_CONTENT
                            && status != HttpServletResponse.SC_NOT_MODIFIED && !isCommitted()) {
                        setHeader(HttpHeaders.CACHE_CONTROL, NOT_KEPT);
                    }
                }
            });
        } catch (IOException | ServletException | RuntimeException e) {
            if (!response.isCommitted()) {
                response.setHeader(HttpHeaders.CACHE_CONTROL, NOT_KEPT);   // the container answers 500 with what is set here
            }
            throw e;
        }
    }
}
