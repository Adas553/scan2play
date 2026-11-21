package com.scan2play.deck;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The window's own answers, before anything goes to the server. */
class WindowTest {

    @Test
    void anEmptyCode_asksForTheCode_insteadOfSayingItDoesNotWork() {
        assertEquals("Wpisz kod z panelu.", Window.beforeSending(""));
        assertEquals("Wpisz kod z panelu.", Window.beforeSending("   "));
        assertNull(Window.beforeSending("123456"), "a code typed goes to the server");
    }
}
