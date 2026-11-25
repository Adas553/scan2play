package com.scan2play.template;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The privacy policy's words about the news e-mail (V80), PL and EN: the address never used for any other advertising, the consent the
 * owner's own (said without a masculine "sam"), and section 3 has the anchor the consent's link in the panel and in "Ustawienia imprezy"
 * leads to ({@code /privacy#dataUse}).
 */
class PrivacyNewsConsentTest {

    private static String source(String page) throws IOException {
        try (InputStream in = PrivacyNewsConsentTest.class.getResourceAsStream("/templates/" + page + ".html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void thePolishPolicy_promisesNoOtherAdvertising_andSaysItImpersonally() throws IOException {
        String pl = source("privacy_pl");

        assertThat(pl).contains("tylko gdy właściciel to włączy (pkt 3); nigdy w innych celach reklamowych;",
                        "wysyłany tylko wtedy, gdy właściciel włączy „Nowości mailem”", "<h3 id=\"dataUse\">3. ")
                .doesNotContain("innych firm", "gdy sam ");
    }

    @Test
    void theEnglishPolicy_saysTheSame() throws IOException {
        String en = source("privacy");

        assertThat(en).contains("only once the owner switches that on (section 3); never for any other advertising;",
                        "<h3 id=\"dataUse\">3. ")
                .doesNotContain("other companies");
    }
}
