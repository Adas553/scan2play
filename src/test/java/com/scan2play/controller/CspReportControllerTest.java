package com.scan2play.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browsers' reports of the Content-Security-Policy (review item 5.1): logged once per violation, without the page's query, and
 * whatever arrives is answered 204 — the endpoint is public.
 */
@ExtendWith(OutputCaptureExtension.class)
class CspReportControllerTest {

    private final CspReportController controller = new CspReportController();

    private static MockHttpServletRequest report(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/csp-report");
        request.setContentType("application/csp-report");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static final String INLINE_SCRIPT = "{\"csp-report\":{\"document-uri\":\"https://scan2play.com.pl/p/ABC12?lang=pl\","
            + "\"effective-directive\":\"script-src-elem\",\"blocked-uri\":\"inline\",\"source-file\":\"https://scan2play.com.pl/p/ABC12\","
            + "\"line-number\":113}}";

    @Test
    void aViolation_isLoggedOnce_withoutTheQueryOfThePage(CapturedOutput output) {
        assertThat(controller.report(report(INLINE_SCRIPT)).getStatusCode().value()).isEqualTo(204);
        controller.report(report(INLINE_SCRIPT));   // the next load of the same page

        assertThat(output.getOut()).contains("CSP violation: script-src-elem blocked 'inline' on https://scan2play.com.pl/p/ABC12 (from")
                .doesNotContain("lang=pl");
        assertThat(output.getOut().split("CSP violation", -1)).hasSize(2);
    }

    @Test
    void anythingElse_isAnswered204_andNotLogged(CapturedOutput output) {
        assertThat(controller.report(report("not json")).getStatusCode().value()).isEqualTo(204);
        assertThat(controller.report(report("{\"other\":1}")).getStatusCode().value()).isEqualTo(204);
        assertThat(controller.report(report("{\"csp-report\":{\"blocked-uri\":\"" + "x".repeat(20_000) + "\"}}"))
                .getStatusCode().value()).as("cut at 8 KB: no longer JSON").isEqualTo(204);

        assertThat(output.getOut()).doesNotContain("CSP violation");
    }

    @Test
    void longFields_areCutShort(CapturedOutput output) {
        controller.report(report("{\"csp-report\":{\"document-uri\":\"https://a/b\",\"violated-directive\":\"img-src\","
                + "\"blocked-uri\":\"https://evil.example/" + "y".repeat(1000) + "\"}}"));

        assertThat(output.getOut()).contains("CSP violation: img-src blocked 'https://evil.example/").doesNotContain("y".repeat(300));
    }
}
