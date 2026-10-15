package com.scan2play;

import com.scan2play.config.SecurityConfig;
import com.scan2play.controller.CspReportController;
import com.scan2play.controller.HomeController;
import com.scan2play.controller.LegalController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Smoke test — verifies that the application boots, routes are wired correctly,
 * and the security layer works as expected.
 * Uses @WebMvcTest (no DB, no external APIs — fast and reliable).
 */
@WebMvcTest({HomeController.class, CspReportController.class, LegalController.class, com.scan2play.controller.SeoController.class})
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class})
class SmokeTest {

    @Autowired
    private MockMvc mockMvc;

    // ---- Public routes ----

    // ---- Search engines and shared links (2026-10-14) ----

    @Test
    @DisplayName("GET /robots.txt → public, plain text: the DJ's pages and the login not for a crawler, where the sitemap is")
    void robotsTxt_isPublic_andNamesTheSitemap() throws Exception {
        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(containsString("User-agent: *")))
                .andExpect(content().string(containsString("Disallow: /dj/")))
                .andExpect(content().string(containsString("Disallow: /oauth2/")))
                .andExpect(content().string(containsString("Sitemap: https://www.scan2play.com.pl/sitemap.xml")))
                .andExpect(content().string(not(containsString("Disallow: /p/"))));   // crawled, to see its noindex
    }

    @Test
    @DisplayName("GET /sitemap.xml → public: the public pages, Polish at their own address and English with ?lang=en, each with its alternates; no /pro while the plans are off")
    void sitemap_listsThePublicPages_inBothLanguages() throws Exception {
        String xml = mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/xml"))
                .andReturn().getResponse().getContentAsString();
        assertThat(xml).contains("<loc>https://www.scan2play.com.pl/</loc>", "<loc>https://www.scan2play.com.pl/?lang=en</loc>",
                "<loc>https://www.scan2play.com.pl/jak-to-dziala</loc>", "<loc>https://www.scan2play.com.pl/privacy</loc>",
                "<loc>https://www.scan2play.com.pl/terms</loc>",
                "hreflang=\"pl\" href=\"https://www.scan2play.com.pl/jak-to-dziala\"",
                "hreflang=\"en\" href=\"https://www.scan2play.com.pl/jak-to-dziala?lang=en\"", "hreflang=\"x-default\"")
                .doesNotContain("/pro", "/dj/", "/p/");
    }

    @Test
    @DisplayName("The landing page with no language from the browser (a search engine's crawler) is Polish, its own address canonical, the English one its alternate")
    void landingPage_withoutALanguage_isPolish_withItsAddresses() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"pl\"")))
                .andExpect(content().string(containsString("<title>Scan2Play – prośby o piosenki od gości przez kod QR</title>")))
                .andExpect(content().string(containsString("<meta name=\"description\" content=\"Goście skanują kod QR")))
                .andExpect(content().string(containsString("<link rel=\"canonical\" href=\"https://www.scan2play.com.pl/\">")))
                .andExpect(content().string(containsString("<link rel=\"alternate\" hreflang=\"en\" href=\"https://www.scan2play.com.pl/?lang=en\">")))
                .andExpect(content().string(containsString("<meta property=\"og:image\" content=\"https://www.scan2play.com.pl/images/og-image.png\">")))
                .andExpect(content().string(containsString("<meta property=\"og:locale\" content=\"pl_PL\">")));
    }

    @Test
    @DisplayName("?lang=en names the English page whatever the browser says; without it the browser's language as before")
    void landingPage_inEnglish_byItsAddress_orByTheBrowser() throws Exception {
        mockMvc.perform(get("/").param("lang", "en").header("Accept-Language", "pl-PL"))
                .andExpect(content().string(containsString("lang=\"en\"")))
                .andExpect(content().string(containsString("<link rel=\"canonical\" href=\"https://www.scan2play.com.pl/?lang=en\">")))
                .andExpect(content().string(containsString("<meta property=\"og:locale\" content=\"en_US\">")));
        mockMvc.perform(get("/").header("Accept-Language", "en-US"))
                .andExpect(content().string(containsString("lang=\"en\"")));
        mockMvc.perform(get("/").param("lang", "pl").header("Accept-Language", "en-US"))
                .andExpect(content().string(containsString("lang=\"pl\"")));
        mockMvc.perform(get("/").param("lang", "xx").header("Accept-Language", "en-US"))
                .andExpect(content().string(containsString("lang=\"en\"")));
    }

    @Test
    @DisplayName("The guide in English at its Polish path with ?lang=en: the same canonical scheme")
    void guide_hasItsAddresses() throws Exception {
        mockMvc.perform(get("/jak-to-dziala"))
                .andExpect(content().string(containsString("<link rel=\"canonical\" href=\"https://www.scan2play.com.pl/jak-to-dziala\">")));
        mockMvc.perform(get("/how-it-works").param("lang", "en"))
                .andExpect(content().string(containsString("<link rel=\"canonical\" href=\"https://www.scan2play.com.pl/jak-to-dziala?lang=en\">")))
                .andExpect(content().string(containsString("<meta name=\"description\" content=\"A guide for DJs: the QR code")));
        mockMvc.perform(get("/privacy").param("lang", "en"))
                .andExpect(content().string(containsString("<link rel=\"canonical\" href=\"https://www.scan2play.com.pl/privacy?lang=en\">")));
    }

    /**
     * The privacy policy names every provider the data passes through (review 2026-10-14 W14: it said the data went "only" to
     * Google): the hosting in the USA and Cloudflare, and how long the logs with the guests' texts are kept — in both languages.
     */
    @Test
    @DisplayName("The privacy policy names the hosting (Railway, us-east4), Cloudflare and the logs' 7 days, in Polish and in English; no Web Analytics (off)")
    void privacy_namesTheHostingAndCloudflare() throws Exception {
        for (String lang : new String[]{"pl", "en"}) {
            mockMvc.perform(get("/privacy").param("lang", lang))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Railway")))
                    .andExpect(content().string(containsString("us-east4")))
                    .andExpect(content().string(containsString("Cloudflare")))
                    // Cloudflare Web Analytics is off for the site (the owner checked, 2026-10-15): the policy must not say it is used
                    .andExpect(content().string(not(containsString("Web Analytics"))))
                    .andExpect(content().string(containsString(lang.equals("pl") ? "przez 7 dni" : "for 7 days")))
                    // Railway's DPA is signed (2026-10-15): the transfer to the USA rests on the standard contractual clauses
                    .andExpect(content().string(containsString(lang.equals("pl") ? "standardowych klauzulach umownych" : "standard contractual clauses")))
                    .andExpect(content().string(not(containsString(lang.equals("pl") ? "wyłącznie do Google" : "only sent to Google"))));
        }
    }

    @Test
    @DisplayName("The terms in both languages: the head for search engines (canonical, alternates, description) — it vanished unnoticed before")
    void terms_haveTheirHead() throws Exception {
        mockMvc.perform(get("/terms"))
                .andExpect(content().string(containsString("<link rel=\"canonical\" href=\"https://www.scan2play.com.pl/terms\">")))
                .andExpect(content().string(containsString("<meta name=\"description\" content=\"Zasady korzystania ze Scan2Play")));
        mockMvc.perform(get("/terms").param("lang", "en"))
                .andExpect(content().string(containsString("<link rel=\"canonical\" href=\"https://www.scan2play.com.pl/terms?lang=en\">")))
                .andExpect(content().string(containsString("<meta name=\"description\" content=\"The rules for using Scan2Play")));
    }

    @Test
    @DisplayName("The picture of a shared link is served without a login")
    void ogImage_isPublic() throws Exception {
        mockMvc.perform(get("/images/og-image.png")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /pro → public, but a 404 while the plans are not enforced (V35): nothing says \"Pro\" yet")
    void proPage_isNotThere_whileThePlansAreNotEnforced() throws Exception {
        mockMvc.perform(get("/pro"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET / → 200 landing page for unauthenticated user")
    void landingPage_shouldReturn200() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("landing"));
    }

    @Test
    @DisplayName("The scripts and styles under the deploy's version are served without a login (the pages link them so)")
    void stylesAndScripts_underTheDeploysVersion_areServedWithoutLogin() throws Exception {
        // "dev" without RAILWAY_GIT_COMMIT_SHA (application.properties). That the pages' "@{/css/...}" become these addresses is
        // Spring's ResourceUrlEncodingFilter, which this slice does not register (checked on the running app)
        mockMvc.perform(get("/dev/css/app.css")).andExpect(status().isOk())
                // locally the files change under "dev" with every edit: never kept (VersionedAssetCacheFilter)
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mockMvc.perform(get("/dev/js/dashboard/main.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("import './settings-rows.js';")));
        mockMvc.perform(get("/dev/js/dashboard/settings-page.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("import './push.js';")));
        mockMvc.perform(get("/dev/js/dashboard/push.js")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Landing page renders HTML with Scan2Play branding")
    void landingPage_shouldContainBranding() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Scan2Play")));
    }

    @Test
    @DisplayName("The landing page offers one kind of party, the one for DJs, through /start; no YouTube, no Spotify")
    void landingPage_offersTheTileForDjs() throws Exception {
        String html = mockMvc.perform(get("/")).andReturn().getResponse().getContentAsString();

        assertThat(html).containsOnlyOnce("href=\"/start\"").containsOnlyOnce("class=\"provider-card ")
                .doesNotContain("/start/youtube", "/start/requests", "spotify", "YouTube API Services");
        assertThat(html).as("our mark above the title").contains("<img src=\"/images/logo.svg\" alt=\"\" class=\"s2p-hero-mark mb-4\">");
    }

    @Test
    @DisplayName("GET /start (public) goes on to Google's login; so do the old links of the tiles")
    void start_goesToGooglesLogin() throws Exception {
        mockMvc.perform(get("/start"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/oauth2/authorization/google"));
        mockMvc.perform(get("/start/requests")).andExpect(redirectedUrl("/oauth2/authorization/google"));
        mockMvc.perform(get("/start/youtube")).andExpect(redirectedUrl("/oauth2/authorization/google"));
    }

    @Test
    @DisplayName("Every page carries the Content-Security-Policy — reported only, until it is switched on (review 5.1)")
    void pages_shouldCarryTheContentSecurityPolicy_reportOnly() throws Exception {
        mockMvc.perform(get("/"))
                // no 'unsafe-inline' for scripts: the pages have no inline script and no inline handler
                .andExpect(header().string("Content-Security-Policy-Report-Only", containsString(
                        "script-src 'self';")))
                // nothing of YouTube: no player, no frame (frames fall back to default-src 'self')
                .andExpect(header().string("Content-Security-Policy-Report-Only", not(containsString("youtube"))))
                .andExpect(header().string("Content-Security-Policy-Report-Only", not(containsString("frame-src"))))
                // and none for styles: the templates have no style="…" (NoInlineCodeInTemplatesTest)
                .andExpect(header().string("Content-Security-Policy-Report-Only", containsString(
                        "style-src 'self';")))
                .andExpect(header().string("Content-Security-Policy-Report-Only", not(containsString("'unsafe-inline'"))))
                // no other host for scripts and styles: Bootstrap comes from the app (/webjars)
                .andExpect(header().string("Content-Security-Policy-Report-Only", not(containsString("cdn.jsdelivr.net"))))
                .andExpect(header().string("Content-Security-Policy-Report-Only", containsString("object-src 'none'")))
                .andExpect(header().string("Content-Security-Policy-Report-Only", containsString("report-uri /csp-report")))
                .andExpect(header().doesNotExist("Content-Security-Policy"));
    }

    @Test
    @DisplayName("A browser's CSP report is taken without a login or a CSRF token")
    void cspReport_isTakenWithoutLoginOrCsrf() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/csp-report")
                        .contentType("application/csp-report")
                        .content("{\"csp-report\":{\"document-uri\":\"https://scan2play.com.pl/p/ABC12?x=1\","
                                + "\"effective-directive\":\"script-src-elem\",\"blocked-uri\":\"inline\"}}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Static CSS asset is accessible")
    void staticCss_shouldBeAccessible() throws Exception {
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Static JS asset is accessible")
    void staticJs_shouldBeAccessible() throws Exception {
        mockMvc.perform(get("/js/dashboard/main.js"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/js/dashboard/events.js"))   // a module imported by the others
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Bootstrap is served by the app without a login, its version left out of the URL (webjars-locator-lite)")
    void bootstrap_isServedByTheApp() throws Exception {
        mockMvc.perform(get("/webjars/bootstrap/css/bootstrap.min.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bootstrap")));
        mockMvc.perform(get("/webjars/bootstrap/js/bootstrap.bundle.min.js"))
                .andExpect(status().isOk());
    }

    // ---- Protected routes (security check) ----

    @Test
    @DisplayName("GET /dj/dashboard → 302 redirect for unauthenticated user")
    void dashboard_shouldRedirectUnauthenticated() throws Exception {
        mockMvc.perform(get("/dj/dashboard"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("GET /dj/history-view → 302 redirect for unauthenticated user")
    void historyView_shouldRedirectUnauthenticated() throws Exception {
        mockMvc.perform(get("/dj/history-view"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("Landing page HTML does not contain any <iframe> (there is no player)")
    void landingPage_shouldNotContainIframe() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<iframe"))));
    }

    @Test
    @DisplayName("The landing page's guide, in both languages, describes no player, Auto-Pilot or background playlist")
    void landingGuide_describesOnlyTheRequestsParty() throws Exception {
        for (String language : new String[] {"en", "pl"}) {
            String html = mockMvc.perform(get("/").header("Accept-Language", language)).andReturn().getResponse().getContentAsString();

            // (without messages_en, "en" gets the JVM's own locale's bundle — Polish on a Polish machine; the check holds for both)
            assertThat(html).as(language).doesNotContain("Auto-Pilot", "Playlist", "Shuffle", "Losow", "YouTube")
                    .containsAnyOf("Jak to działa?", "How does it work?");
        }
    }

    @Test
    @DisplayName("The privacy policy and the terms, in both languages, use no YouTube API, keep no YouTube data, and link YouTube's terms")
    void legalPages_useNoYouTubeApi() throws Exception {
        for (String page : new String[] {"/privacy", "/terms"}) {
            for (String language : new String[] {"en", "pl"}) {
                String html = mockMvc.perform(get(page).header("Accept-Language", language))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

                assertThat(html).as(page + " " + language)
                        .doesNotContain("YouTube API Services", "Data API", "YouTube API,", "cache", "Cache", "playlist", "playlisty")
                        .contains("https://www.youtube.com/t/terms", "🔍", language.equals("pl") ? "Podejrzyj" : "Preview");
            }
        }
    }

    /**
     * A browser in a language without a bundle of its own (German, or English — the English texts are the base bundle) gets the
     * English texts, whatever the server's own language: on a Polish machine it used to get Polish ones (spring.messages
     * .fallback-to-system-locale=false).
     */
    @Test
    @DisplayName("On a Polish server, a German or English browser gets the English page, a Polish one the Polish page")
    void aBrowserInALanguageWithoutABundle_getsEnglish_evenOnAPolishServer() throws Exception {
        Locale serverLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("pl-PL"));
        try {
            for (String language : new String[] {"de", "en", "en-US"}) {
                mockMvc.perform(get("/").header("Accept-Language", language))
                        .andExpect(content().string(containsString("lang=\"en\"")));
            }
            mockMvc.perform(get("/").header("Accept-Language", "pl")).andExpect(content().string(containsString("lang=\"pl\"")));
        } finally {
            Locale.setDefault(serverLocale);
        }
    }

    /** The window of the per-network limit (GuestRequestLimiter): as long as the guest's IP address stays in memory. */
    @Value("${guest.limit.per-ip-window-minutes}")
    private int perNetworkWindowMinutes;

    @Test
    @DisplayName("The privacy policy, in both languages, names the guest's IP address and how long it is kept — the per-network window")
    void privacyPolicy_namesTheGuestsIpAddress() throws Exception {
        for (String language : new String[] {"en", "pl"}) {
            String html = mockMvc.perform(get("/privacy").header("Accept-Language", language))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            assertThat(html).as(language).contains(language.equals("pl") ? "Adres IP" : "IP address",
                    perNetworkWindowMinutes + (language.equals("pl") ? " minut po ostatniej prośbie" : " minutes after the last request"));
        }
    }

    @Test
    @DisplayName("The privacy policy, in both languages, names the DJ's notifications: the push services that deliver them, encrypted")
    void privacyPolicy_namesThePushServicesOfTheNotifications() throws Exception {
        for (String language : new String[] {"en", "pl"}) {
            String html = mockMvc.perform(get("/privacy").header("Accept-Language", language))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            assertThat(html).as(language).contains("Google, Apple, Mozilla",
                    language.equals("pl") ? "zaszyfrowana od końca do końca" : "end-to-end encrypted");
        }
    }

    @Test
    @DisplayName("The privacy policy, in both languages, names the invited person's e-mail address, that nothing is sent to it, and how long it waits")
    void privacyPolicy_namesTheAddressOfAnInvitationByEmail() throws Exception {
        for (String language : new String[] {"en", "pl"}) {
            String html = mockMvc.perform(get("/privacy").header("Accept-Language", language))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            int days = com.scan2play.entity.StaffInvitationEntity.MAX_AGE_DAYS;
            assertThat(html).as(language).contains(language.equals("pl")
                    ? new String[] {"po adresie e-mail jego konta Google", "Na ten adres nic nie wysyłamy", "najdłużej " + days + " dni"}
                    : new String[] {"by the e-mail address of their Google account", "No e-mail is sent to it", "at most " + days + " days"});
        }
    }
}
