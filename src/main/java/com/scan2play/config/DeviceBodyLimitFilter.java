package com.scan2play.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * A call of the DJ's program ({@code /api/device/*}, public: no login, no session) is a few lines of JSON, at most 20 songs of 2 × 500
 * characters. Spring reads a JSON body whole before the controller looks at the token, and Tomcat limits only forms
 * ({@code max-http-form-post-size}): without this a stranger could have the app parse megabytes per request. A call that does not say
 * its length (chunked) or says more than {@value #MAX_BYTES} bytes is refused before anything is read — the program's HTTP client
 * always sends the length.
 */
@Component
public class DeviceBodyLimitFilter extends OncePerRequestFilter {

    /** 20 songs × 1 000 characters × 4 bytes, and the room for the rest of the JSON. */
    static final int MAX_BYTES = 128 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/device/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length > MAX_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        } else if (length < 0 && !"GET".equals(request.getMethod())) {
            response.sendError(HttpServletResponse.SC_LENGTH_REQUIRED);
        } else {
            chain.doFilter(request, response);
        }
    }
}
