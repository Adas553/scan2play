package com.scan2play;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session cookie as the running app sends it — the embedded Tomcat, where Spring Boot builds the cookie serializer from
 * {@code server.servlet.session.cookie.*}; a MockMvc test builds it from the mock servlet context instead and sees none of them.
 * Over plain http, as a proxy's lost {@code X-Forwarded-Proto} would look: {@code Secure} must not depend on the request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "GOOGLE_AI_API_KEY=it-dummy",
        "GOOGLE_CLIENT_ID=it-dummy",
        "GOOGLE_CLIENT_SECRET=it-dummy"
})
class SessionCookieIT extends PostgresIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void theLoginPagesSessionCookie_isSecure_httpOnly_andLax() throws Exception {
        HttpResponse<Void> page = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login")).GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(page.statusCode()).isEqualTo(200);
        String cookie = page.headers().allValues("Set-Cookie").stream().filter(header -> header.startsWith("SESSION="))
                .findFirst().orElseThrow();
        assertThat(cookie).contains("; Secure", "; HttpOnly", "; SameSite=Lax", "; Path=/").doesNotContain("Max-Age", "Expires");
    }
}
