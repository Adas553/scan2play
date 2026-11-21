package com.scan2play.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A call of the DJ's program ({@code /api/device/*}, public: no login, no session) is a few lines of JSON, at most 20 songs of 2 × 500
 * characters. Spring reads a JSON body whole before the controller looks at the token, and Tomcat limits only forms
 * ({@code max-http-form-post-size}): without this a stranger could have the app parse megabytes per request. A body that says it is
 * over {@value #MAX_BYTES} bytes is refused unread (413); any other is read up to one byte past the limit — a body without its length
 * (chunked, as a proxy between the program and the app may forward it) is not refused for that — and refused when longer, else
 * passed on from memory.
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
        if (request.getContentLengthLong() > MAX_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        chain.doFilter(new ReadBody(request, body), response);
    }

    /** The request with its body already read. */
    private static final class ReadBody extends HttpServletRequestWrapper {

        private final byte[] body;

        ReadBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("read from memory");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String charset = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body),
                    charset == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(charset)));
        }
    }
}
