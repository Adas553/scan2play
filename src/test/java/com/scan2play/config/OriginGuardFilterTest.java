package com.scan2play.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Railway answers our domain at its own address too, past Cloudflare — where a client may send any CF-Connecting-IP. With the secret
 * set only a request carrying Cloudflare's header reaches the app; Railway's healthcheck from inside still does; without the secret
 * (locally, the tests, Railway before the Cloudflare rule) nothing changes.
 */
class OriginGuardFilterTest {

    private static final String SECRET = "s3cret-from-cloudflare";

    /** The status, and whether the request went on to the app. */
    private record Outcome(int status, boolean passed) {
    }

    private static Outcome send(String secret, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new OriginGuardFilter(secret).doFilter(request, response, chain);
        return new Outcome(response.getStatus(), chain.getRequest() != null);
    }

    private static MockHttpServletRequest get(String path, String header) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServerName("www.scan2play.com.pl");
        if (header != null) {
            request.addHeader(OriginGuardFilter.HEADER, header);
        }
        return request;
    }

    @Test
    void throughCloudflare_theRequestGoesOn() throws Exception {
        assertThat(send(SECRET, get("/p/ABC12", SECRET))).isEqualTo(new Outcome(200, true));
    }

    @Test
    void pastCloudflare_withoutTheHeader_orAWrongOne_isRefused() throws Exception {
        assertThat(send(SECRET, get("/p/ABC12", null))).isEqualTo(new Outcome(403, false));
        assertThat(send(SECRET, get("/p/ABC12", "guess"))).isEqualTo(new Outcome(403, false));
        assertThat(send(SECRET, get("/p/ABC12", SECRET + "x"))).isEqualTo(new Outcome(403, false));
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/p/ABC12/request");
        post.addHeader("CF-Connecting-IP", "203.0.113.7");   // a made-up address: exactly what the guard is for
        assertThat(send(SECRET, post)).isEqualTo(new Outcome(403, false));
    }

    @Test
    void railwaysHealthcheck_fromInside_goesOn() throws Exception {
        MockHttpServletRequest check = new MockHttpServletRequest("GET", "/privacy");
        check.setServerName(OriginGuardFilter.RAILWAY_HEALTHCHECK_HOST);
        assertThat(send(SECRET, check)).isEqualTo(new Outcome(200, true));
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/p/ABC12/request");
        post.setServerName(OriginGuardFilter.RAILWAY_HEALTHCHECK_HOST);
        assertThat(send(SECRET, post)).as("only its GET").isEqualTo(new Outcome(403, false));
    }

    @Test
    void withoutTheSecret_theGuardIsOff() throws Exception {
        assertThat(send("", get("/", null))).isEqualTo(new Outcome(200, true));
        assertThat(send("  ", get("/", null))).isEqualTo(new Outcome(200, true));
    }
}
