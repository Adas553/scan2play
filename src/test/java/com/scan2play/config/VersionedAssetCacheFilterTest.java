package com.scan2play.config;

import com.scan2play.controller.HomeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The scripts and styles under a deploy's version are kept for a year, Bootstrap and the images a day; nothing else is
 * ({@link VersionedAssetCacheFilter}). The
 * version as Railway sets it — a commit — in place of the local "dev" (for that one, see {@link #theLocalVersionIsNeverKept}).
 */
@WebMvcTest(value = HomeController.class, properties = "spring.web.resources.chain.strategy.fixed.version=0123abcd")
@Import({SecurityConfig.class, com.scan2play.service.PlansOffConfig.class})
class VersionedAssetCacheFilterTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.scan2play.service.SiteCounter siteCounter;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("a script and a style under the deploy's version: kept for a year, by the browsers and Cloudflare alike")
    void theDeploysScriptsAndStyles_areKeptForAYear() throws Exception {
        mockMvc.perform(get("/0123abcd/js/dashboard/main.js")).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable"))
                .andExpect(header().doesNotExist(HttpHeaders.PRAGMA))
                .andExpect(header().doesNotExist(HttpHeaders.EXPIRES));
        mockMvc.perform(get("/0123abcd/css/app.css")).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable"));
    }

    @Test
    @DisplayName("an address without the version, an old deploy's one, a page: Spring Security's no-store as before")
    void everythingElse_isNotKept() throws Exception {
        mockMvc.perform(get("/js/dashboard/main.js")).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mockMvc.perform(get("/fedcba98/js/dashboard/main.js"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mockMvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mockMvc.perform(get("/0123abcd/images/icon-192.png"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    /**
     * The review of 2026-10-21, P1: Bootstrap (31 KB gzip), the logo, the icons and the landing page's pictures went with
     * {@code no-store} — every phone fetched them again on every page, through Railway. Their addresses carry no version, so a day,
     * not a year: a new Bootstrap or picture reaches everyone by the next day (Cloudflare's copy: purge it after such a change).
     */
    @Test
    @DisplayName("Bootstrap and the images: kept for a day")
    void bootstrapAndTheImages_areKeptForADay() throws Exception {
        for (String path : new String[]{"/webjars/bootstrap/css/bootstrap.min.css", "/webjars/bootstrap/js/bootstrap.bundle.min.js",
                "/images/logo.svg", "/images/icon-192.png", "/images/landing/guest-pl.webp", "/favicon.ico"}) {
            mockMvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=86400"))
                    .andExpect(header().doesNotExist(HttpHeaders.PRAGMA))
                    .andExpect(header().doesNotExist(HttpHeaders.EXPIRES));
        }
        mockMvc.perform(get("/images/no-such-image.png")).andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    /**
     * The print's own fonts (qr-print.css): public — no login, as the styles that name them — and kept for a year, their version in
     * their file names ("source-sans-3-5.3.1-…"): a new font is a new name. Served as a font.
     */
    @Test
    @DisplayName("the print's fonts: public, kept for a year, served as woff2")
    void thePrintsFonts_arePublic_andKeptForAYear() throws Exception {
        for (String font : new String[]{"source-sans-3-5.3.1-latin.woff2", "source-sans-3-5.3.1-latin-ext.woff2",
                "playfair-display-5.3.0-latin.woff2", "playfair-display-5.3.0-latin-ext.woff2"}) {
            mockMvc.perform(get("/fonts/" + font)).andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable"))
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "font/woff2"));
        }
        mockMvc.perform(get("/fonts/no-such-font.woff2")).andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    @DisplayName("a missing file under the version is not kept for a year: a 404 cached by Cloudflare would outlive the fix (review 2026-10-14)")
    void aMissingFile_isNotKept() throws Exception {
        mockMvc.perform(get("/0123abcd/js/dashboard/no-such-file.js")).andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    @DisplayName("a failure while the file is served is not kept either")
    void aFailure_isNotKept() throws Exception {
        VersionedAssetCacheFilter filter = new VersionedAssetCacheFilter("0123abcd");
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();

        filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest("GET", "/0123abcd/css/app.css"), response,
                (request, failed) -> ((jakarta.servlet.http.HttpServletResponse) failed).setStatus(500));

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    @DisplayName("locally (version \"dev\") nothing is kept: the files change under it with every edit")
    void theLocalVersionIsNeverKept() {
        VersionedAssetCacheFilter local = new VersionedAssetCacheFilter("dev");
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/dev/js/dashboard/main.js");

        assertThat(local.shouldNotFilter(request)).isTrue();
        assertThat(new VersionedAssetCacheFilter("0123abcd").shouldNotFilter(
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/0123abcd/js/dashboard/main.js"))).isFalse();
    }
}
