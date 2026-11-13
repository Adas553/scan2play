package com.scan2play.controller;

import com.scan2play.config.SecurityConfig;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.WishService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The guests' page of "Życzenia przed imprezą" (V68) through the real security and the real limits: a wish goes to the service and back
 * to the page, a guest writes five at most, a closed event shows no form, a token that is no event's is a 404, an expired form comes
 * back to the page. A first visit through Spring Session's filter: {@link WishPageSessionWebTest}. Writes the pages of the browser
 * tests ({@code target/browser-harness/wish*.html}).
 */
@WebMvcTest(WishController.class)
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class, GuestRequestLimiter.class})
class WishWebTest {

    private static final String TOKEN = "Wish_123-xyzWish_123-xy";
    private static final String PAGE = "/w/" + TOKEN;
    /** An event of each test's own: the address limit is the application's, kept from one test to the next. */
    private static final java.util.concurrent.atomic.AtomicLong EVENTS = new java.util.concurrent.atomic.AtomicLong();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WishService wishService;
    @MockitoBean
    private PartySettingsRepository partySettingsRepository;
    @MockitoBean
    private com.scan2play.service.SiteCounter siteCounter;

    private WishService.Event event;

    @BeforeEach
    void setUp() {
        event = event(false);
        when(partySettingsRepository.findByPartyCode("WSH01")).thenReturn(Optional.of(
                PartySettingsEntity.builder().partyCode("WSH01").ownerId("owner").active(false).djName("DJ Koko").build()));
        when(wishService.byToken(anyString())).thenReturn(Optional.empty());
        when(wishService.byToken(TOKEN)).thenAnswer(call -> Optional.of(event));
        when(wishService.add(eq(TOKEN), anyString())).thenReturn(WishService.Added.ADDED);
    }

    static WishService.Event event(boolean closed) {
        return new WishService.Event(EVENTS.incrementAndGet(), "WSH01", TOKEN, "Wesele Ani i Kuby", java.time.LocalDate.of(2099, 6, 14),
                closed, 12, Instant.now());
    }

    static String csrf(String html) {
        Matcher token = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertThat(token.find()).as("the form's CSRF field").isTrue();
        return token.group(1);
    }

    static String html(MvcResult result) throws IOException {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** A page of the browser tests: the stand-in serves the files at /js/, /css/ — not under the deploy's version. */
    static void write(String name, String html) throws IOException {
        Path out = Path.of("target", "browser-harness", name);
        Files.createDirectories(out.getParent());
        Files.writeString(out, html.replaceAll("\"/[^/\"]+/(js|css)/", "\"/$1/"), StandardCharsets.UTF_8);
    }

    @Test
    void thePage_hasTheEventAndTheForm_inTheGuestsLanguage() throws Exception {
        MvcResult result = mockMvc.perform(get(PAGE).header("Accept-Language", "pl-PL")).andExpect(status().isOk())
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow")).andReturn();
        assertThat(result.getResolvedException()).isNull();
        String html = html(result);
        assertThat(html).contains("<title>Twoja piosenka na imprezę - Scan2Play</title>", ">Wesele Ani i Kuby<", ">14 czerwca 2099<",
                "🎧 Gra:", "DJ Koko", "action=\"" + PAGE + "\"", "name=\"song\"", "data-autocomplete=\"songs\"",
                ">Jaką piosenkę chcesz usłyszeć na imprezie?<", "Do wpisania zostało: 5.", ">Wyślij życzenie<",
                "Nie zapisujemy Twojego imienia ani adresu", "<meta name=\"referrer\" content=\"no-referrer\">", "<footer", "</html>");
        csrf(html);
        assertThat(html).as("no other guest's wish on the page: they are not checked by the AI").doesNotContain("wishRows", "×");
        write("wish.html", html);

        String english = html(mockMvc.perform(get(PAGE).header("Accept-Language", "en")).andExpect(status().isOk()).andReturn());
        assertThat(english).contains(">14 June 2099<", ">Which song do you want to hear at the party?<", ">Send my wish<");
    }

    @Test
    void aWish_goesToTheService_andThePageSaysSo_fiveAtMost() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrf(html(mockMvc.perform(get(PAGE).session(session)).andReturn()));
        for (int i = 1; i <= WishController.PER_GUEST; i++) {
            mockMvc.perform(post(PAGE).session(session).param("_csrf", token).param("song", "Wilki – Baśka"))
                    .andExpect(redirectedUrl(PAGE)).andExpect(flash().attribute(ViewAttributes.WISH_ADDED, "Wilki - Baśka"));
        }
        mockMvc.perform(post(PAGE).session(session).param("_csrf", token).param("song", "ABBA - Dancing Queen"))
                .andExpect(redirectedUrl(PAGE)).andExpect(flash().attributeExists(ViewAttributes.WISH_NOTE));
        verify(wishService, times(WishController.PER_GUEST)).add(TOKEN, "Wilki – Baśka");
        verify(wishService, never()).add(TOKEN, "ABBA - Dancing Queen");

        // the sixth's note, and no field any more
        String all = html(mockMvc.perform(get(PAGE).session(session)).andExpect(status().isOk()).andReturn());
        assertThat(all).contains("Masz już 5 życzeń — tyle wystarczy.", "id=\"wishAllWritten\"").doesNotContain("id=\"wishForm\"");
        // as the page after the fifth said it
        String last = html(mockMvc.perform(get(PAGE).session(session).flashAttr(ViewAttributes.WISH_ADDED, "Wilki - Baśka"))
                .andExpect(status().isOk()).andReturn());
        assertThat(last).contains("✓ Zapisane: Wilki - Baśka", "To było Twoje ostatnie życzenie.")
                .doesNotContain("id=\"wishForm\"", "id=\"wishAllWritten\"");
        write("wish-last.html", last);
    }

    @Test
    void aWishAdded_saysHowManyAreLeft() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrf(html(mockMvc.perform(get(PAGE).session(session)).andReturn()));
        mockMvc.perform(post(PAGE).session(session).param("_csrf", token).param("song", "Wilki - Baśka")).andExpect(redirectedUrl(PAGE));
        String html = html(mockMvc.perform(get(PAGE).session(session).flashAttr(ViewAttributes.WISH_ADDED, "Wilki - Baśka")).andReturn());
        assertThat(html).contains("✓ Zapisane: Wilki - Baśka", "Możesz dopisać jeszcze piosenek: 4.", "id=\"wishForm\"", "Do wpisania zostało: 4.");
        write("wish-added.html", html);
    }

    @Test
    void anEmptyWish_isNotSent_andAFullEvent_saysSo() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrf(html(mockMvc.perform(get(PAGE).session(session)).andReturn()));
        mockMvc.perform(post(PAGE).session(session).param("_csrf", token).param("song", "  !! "))
                .andExpect(flash().attribute(ViewAttributes.WISH_NOTE, "Wpisz wykonawcę i tytuł piosenki."));
        verify(wishService, never()).add(anyString(), eq("  !! "));
        when(wishService.add(eq(TOKEN), anyString())).thenReturn(WishService.Added.FULL);
        mockMvc.perform(post(PAGE).session(session).param("_csrf", token).param("song", "Wilki - Baśka"))
                .andExpect(flash().attribute(ViewAttributes.WISH_NOTE, "Lista życzeń na tę imprezę jest już pełna. Dziękujemy!"))
                .andExpect(flash().attributeCount(1));
    }

    @Test
    void aClosedEvent_showsNoForm() throws Exception {
        event = event(true);
        String html = html(mockMvc.perform(get(PAGE)).andExpect(status().isOk()).andReturn());
        assertThat(html).contains("id=\"wishClosed\"", "Zbieranie życzeń zakończone.").doesNotContain("id=\"wishForm\"");
        write("wish-closed.html", html);
    }

    @Test
    void aTokenThatIsNoEvents_isA404() throws Exception {
        mockMvc.perform(get("/w/nothing-here")).andExpect(status().isNotFound());
        when(partySettingsRepository.findByPartyCode("WSH01")).thenReturn(Optional.empty());
        mockMvc.perform(get(PAGE)).andExpect(status().isNotFound());
    }

    /** A page left open longer than the session (its CSRF token gone): back to the page, saying to send it again — not a 403. */
    @Test
    void anExpiredForm_comesBackToThePage() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post(PAGE).session(session).param("_csrf", "an-old-token").param("song", "Wilki - Baśka"))
                .andExpect(redirectedUrl(PAGE));
        assertThat(html(mockMvc.perform(get(PAGE).session(session)).andReturn()))
                .contains("id=\"wishNote\"", "Strona była otwarta za długo — wyślij piosenkę jeszcze raz.");
        verify(wishService, never()).add(anyString(), anyString());
    }
}
