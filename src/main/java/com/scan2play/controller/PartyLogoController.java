package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlanFeature;
import com.scan2play.service.LogoImage;
import com.scan2play.service.LogoImage.RefusedLogoException;
import com.scan2play.service.PartyLogoService;
import com.scan2play.service.PartySettingsQueryService;
import com.scan2play.service.PlanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.MessageSource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The DJ's logo (V45, plan Pro): the organiser uploads it on "Ustawienia imprezy" (the card "Twoje logo", logo.js), replaces or
 * deletes it; the guests' pages, the TV, the print and the summary show it from {@code /p/{code}/logo.png} — our own address, so the
 * CSP needs no other host.
 * <p>
 * The picture comes as the request's body, as the library's file (see {@code DjLibraryController}: a form's upload would be read
 * before anything checked who sent it), at most {@link LogoImage#MAX_BYTES}, and becomes our own PNG ({@link LogoImage}). Two are
 * decoded at a time on the whole server: a decode holds up to some tens of megabytes of a half-gigabyte heap.
 */
@Controller
public class PartyLogoController {

    /** How long a browser or Cloudflare keeps the logo: a new logo is a new address, a deleted one is gone within this. */
    static final Duration MAX_AGE = Duration.ofHours(1);

    private final Semaphore decoding = new Semaphore(2);

    private final PartyLogoService logoService;
    private final PartySettingsQueryService partySettingsQueryService;
    private final DjSessionHelper sessionHelper;
    private final PlanService planService;
    private final MessageSource messageSource;

    public PartyLogoController(PartyLogoService logoService, PartySettingsQueryService partySettingsQueryService,
                               DjSessionHelper sessionHelper, PlanService planService, MessageSource messageSource) {
        this.logoService = logoService;
        this.partySettingsQueryService = partySettingsQueryService;
        this.sessionHelper = sessionHelper;
        this.planService = planService;
        this.messageSource = messageSource;
    }

    /** The party's logo is now the body's picture: the answer is one sentence for the page — saved, or why not. */
    @PostMapping(path = "/dj/logo", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseBody
    public ResponseEntity<String> upload(@RequestParam String partyCode, HttpServletRequest request, Locale locale,
                                         OAuth2AuthenticationToken authentication, HttpSession session) throws IOException {
        PartySettingsEntity party = planService.require(sessionHelper.requireOwner(partyCode, authentication, session),
                PlanFeature.LOGO);
        if (request.getContentLengthLong() > LogoImage.MAX_BYTES) {
            return answer(HttpStatus.PAYLOAD_TOO_LARGE, "settings.logo.error.TOO_LARGE", locale);
        }
        byte[] file;
        try (InputStream in = request.getInputStream()) {
            file = in.readNBytes(LogoImage.MAX_BYTES + 1);   // one byte more tells a body without a length that is too long
        }
        LogoImage.Logo logo;
        try {
            if (!decoding.tryAcquire(10, TimeUnit.SECONDS)) {
                return answer(HttpStatus.SERVICE_UNAVAILABLE, "settings.logo.error.BUSY", locale);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return answer(HttpStatus.SERVICE_UNAVAILABLE, "settings.logo.error.BUSY", locale);
        }
        try {
            logo = LogoImage.reencode(file);
        } catch (RefusedLogoException e) {
            return answer(e.reason() == LogoImage.Reason.TOO_LARGE ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.UNPROCESSABLE_ENTITY,
                    "settings.logo.error." + e.reason(), locale);
        } finally {
            decoding.release();
        }
        logoService.replace(party.getPartyCode(), logo);
        return answer(HttpStatus.OK, "settings.logo.uploaded", locale);
    }

    /** "Usuń logo": the organiser's own picture goes whatever the plan. */
    @PostMapping("/dj/logo/delete")
    public String delete(@RequestParam String partyCode, OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity party = sessionHelper.requireOwner(partyCode, authentication, session);
        logoService.delete(party.getPartyCode());
        return "redirect:" + UriComponentsBuilder.fromPath("/dj/settings").queryParam("party", party.getPartyCode())
                .fragment("logoCard").build().toUriString();
    }

    /**
     * The logo, public as the party page: a 404 without one or while the organiser's plan has no logo. The ETag is its version, so a
     * page shown again asks and gets a 304 without the picture being read.
     */
    @GetMapping("/p/{partyCode}/logo.png")
    public ResponseEntity<byte[]> logo(@PathVariable String partyCode,
                                       @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
        PartySettingsEntity party;
        try {
            party = planService.asPlanned(partySettingsQueryService.getSettings(partyCode));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (party.getLogoVersion() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String etag = "\"" + party.getLogoVersion() + "\"";
        CacheControl cache = CacheControl.maxAge(MAX_AGE).cachePublic();
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
        }
        PartyLogoService.Stored stored = logoService.find(partyCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).eTag("\"" + stored.version() + "\"").cacheControl(cache)
                .body(stored.png());
    }

    private ResponseEntity<String> answer(HttpStatus status, String code, Locale locale) {
        return ResponseEntity.status(status).contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(messageSource.getMessage(code, new Object[]{LogoImage.MAX_BYTES / (1024 * 1024), LogoImage.MAX_SIDE}, locale));
    }
}
