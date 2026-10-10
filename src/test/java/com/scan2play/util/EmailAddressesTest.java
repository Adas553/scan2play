package com.scan2play.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The address of an invitation by e-mail (V34): what the organiser may type, and how a login's address is matched against it. */
class EmailAddressesTest {

    @Test
    void anAddress_isTrimmedAndInLowerCase_aTypoIsRefused() {
        assertThat(EmailAddresses.clean("  Ola.Kowalska@Gmail.COM ")).contains("ola.kowalska@gmail.com");
        assertThat(EmailAddresses.clean("bar@klub-ola.com.pl")).contains("bar@klub-ola.com.pl");
        for (String typo : new String[]{null, "", "ola", "ola@gmail", "ola@@gmail.com", "ola @gmail.com", "@gmail.com", "ola@.com",
                "ola@gmail.", "a@b@c.pl"}) {
            assertThat(EmailAddresses.clean(typo)).as(String.valueOf(typo)).isEmpty();
        }
        assertThat(EmailAddresses.clean("a".repeat(250) + "@b.pl")).as("longer than 254").isEmpty();
    }

    /** Gmail delivers "Ola.Kowalska+impreza@googlemail.com" to "olakowalska@gmail.com": the organiser need not know how it is written. */
    @Test
    void aGmailAddress_isMatchedAsGmailDeliversIt() {
        assertThat(EmailAddresses.key("Ola.Kowalska@gmail.com")).isEqualTo("olakowalska@gmail.com");
        assertThat(EmailAddresses.key("ola.kowalska+impreza@googlemail.com")).isEqualTo("olakowalska@gmail.com");
        assertThat(EmailAddresses.key("olakowalska@GMAIL.com")).isEqualTo("olakowalska@gmail.com");
    }

    /** Another domain decides itself whether dots count: kept as it is, only the case ignored. */
    @Test
    void anotherDomain_isMatchedAsItIs() {
        assertThat(EmailAddresses.key("Ola.Kowalska+x@Klub.pl")).isEqualTo("ola.kowalska+x@klub.pl");
        assertThat(EmailAddresses.key("ola.kowalska@klub.pl")).isNotEqualTo(EmailAddresses.key("olakowalska@klub.pl"));
    }
}
