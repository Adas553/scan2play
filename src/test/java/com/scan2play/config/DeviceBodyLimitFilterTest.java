package com.scan2play.config;

import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DJ's program's body limit. A body that does not say its length (chunked — a proxy between the program and the app may send it
 * so) is not refused for that: it is read up to the limit, and passed on whole when it fits.
 */
class DeviceBodyLimitFilterTest {

    /** A request as a proxy may forward it: the body, no Content-Length. */
    private static MockHttpServletRequest chunked(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/device/plays") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        return request;
    }

    @Test
    void aSmallBodyWithoutItsLength_isPassedOnWhole() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] read = new String[1];
        new DeviceBodyLimitFilter().doFilter(chunked("{\"plays\":[]}"), response, new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, jakarta.servlet.ServletResponse servletResponse) throws java.io.IOException {
                read[0] = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(read[0]).isEqualTo("{\"plays\":[]}");
    }

    @Test
    void aBigBodyWithoutItsLength_is413_andGoesNoFurther() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new DeviceBodyLimitFilter().doFilter(chunked("a".repeat(DeviceBodyLimitFilter.MAX_BYTES + 1)), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).as("not passed on").isNull();
    }
}
