package com.scan2play.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Only Cloudflare may talk to the app. Railway answers {@code www.scan2play.com.pl} at its own address too, so a client that goes
 * there skips Cloudflare and may send any {@code CF-Connecting-IP} or {@code X-Forwarded-For}: the guests' limit per address
 * ({@code GuestRequestLimiter}) and the address the app sees would be its to choose. Cloudflare adds {@link #HEADER} with a secret to
 * every request (a Transform Rule); a request without it is refused before anything else runs — no session, no security chain.
 * <p>
 * Off while {@code scan2play.origin-secret} (env {@code ORIGIN_SECRET}) is empty: locally, in the tests, and on Railway until the
 * Cloudflare rule sends the header — set the variable only after that, or every request is refused ({@code PROJECT_CONTEXT.md},
 * Section 10). Railway's own healthcheck comes from inside, with the host {@value #RAILWAY_HEALTHCHECK_HOST}, and is let through.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class OriginGuardFilter extends OncePerRequestFilter {

    /** The header Cloudflare's Transform Rule sets. */
    static final String HEADER = "X-Scan2Play-Origin";

    /** The host of Railway's deploy healthcheck (Railway's documentation, "Healthchecks"). */
    static final String RAILWAY_HEALTHCHECK_HOST = "healthcheck.railway.app";

    private final byte[] secret;

    public OriginGuardFilter(@Value("${scan2play.origin-secret:}") String secret) {
        String value = secret == null ? "" : secret.strip();
        this.secret = value.getBytes(StandardCharsets.UTF_8);
        log.info(value.isEmpty()
                ? "Origin guard off: ORIGIN_SECRET is not set — the app answers requests that did not come through Cloudflare"
                : "Origin guard on: only requests with Cloudflare's header are answered");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return secret.length == 0
                || ("GET".equals(request.getMethod()) && RAILWAY_HEALTHCHECK_HOST.equalsIgnoreCase(request.getServerName()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sent = request.getHeader(HEADER);
        // constant time: the answer's timing must not tell how much of a guess was right
        if (sent == null || !MessageDigest.isEqual(secret, sent.getBytes(StandardCharsets.UTF_8))) {
            // a bare answer, not the error page: nothing of the app (a template, a session) runs for it
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Forbidden");
            return;
        }
        chain.doFilter(request, response);
    }
}
