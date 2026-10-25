package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlanFeature;
import com.scan2play.service.DjLibraryParser;
import com.scan2play.service.DjLibraryParser.Library;
import com.scan2play.service.DjLibraryParser.UnreadableLibraryException;
import com.scan2play.service.DjLibraryService;
import com.scan2play.service.PlanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * "Twoja biblioteka" in the settings (V41, plan Pro): the organiser's rekordbox collection or M3U playlist, read into the songs' keys
 * ({@link DjLibraryParser}) so the queue marks each request "✓" or "✗". The organiser's alone — the staff work with it, never change it.
 * <p>
 * The file comes as the request's body ({@code application/octet-stream}, library.js), not as a form: a form's upload is read by
 * the server to find the CSRF token before anything checks who sent it, so the size allowed for a library would be anyone's to
 * send. A body is read only here, after the login, the CSRF token and the plan, and only up to {@link #MAX_BYTES}.
 */
@Controller
@RequestMapping("/dj/library")
public class DjLibraryController {

    /** The largest file: a rekordbox collection of {@value DjLibraryService#MAX_TRACKS} tracks is about 50 MB. */
    static final long MAX_BYTES = 64L * 1024 * 1024;

    private final DjLibraryService libraryService;
    private final DjSessionHelper sessionHelper;
    private final PlanService planService;
    private final MessageSource messageSource;
    private final long maxBytes;

    public DjLibraryController(DjLibraryService libraryService, DjSessionHelper sessionHelper, PlanService planService,
                               MessageSource messageSource, @Value("${scan2play.library.max-bytes:" + MAX_BYTES + "}") long maxBytes) {
        this.libraryService = libraryService;
        this.sessionHelper = sessionHelper;
        this.planService = planService;
        this.messageSource = messageSource;
        this.maxBytes = maxBytes;
    }

    /**
     * The party's library is now the file — of a rekordbox collection, one {@code playlist} of it when named ("Kluby / Piątek",
     * library.js lists them): the answer is one sentence for the page — how many tracks, or why not.
     */
    @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseBody
    public ResponseEntity<String> upload(@RequestParam String partyCode, @RequestParam(required = false) String playlist,
                                         HttpServletRequest request, Locale locale,
                                         OAuth2AuthenticationToken authentication, HttpSession session) throws IOException {
        PartySettingsEntity party = planService.require(sessionHelper.requireOwner(partyCode, authentication, session),
                PlanFeature.LIBRARY);
        if (request.getContentLengthLong() > maxBytes) {
            return answer(HttpStatus.PAYLOAD_TOO_LARGE, "settings.library.error.TOO_LARGE", locale);
        }
        Library library;
        try (InputStream in = new Bounded(request.getInputStream(), maxBytes)) {
            library = DjLibraryParser.parse(in, DjLibraryService.MAX_TRACKS, playlist);
        } catch (TooLarge e) {
            return answer(HttpStatus.PAYLOAD_TOO_LARGE, "settings.library.error.TOO_LARGE", locale);
        } catch (UnreadableLibraryException e) {
            return answer(HttpStatus.UNPROCESSABLE_ENTITY, "settings.library.error." + e.reason(), locale);
        }
        libraryService.replace(party.getPartyCode(), library);
        return answer(HttpStatus.OK, "settings.library.uploaded", locale, library.tracks());
    }

    /** "Usuń bibliotekę": the organiser's own data goes whatever the plan. */
    @PostMapping("/delete")
    public String delete(@RequestParam String partyCode, OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity party = sessionHelper.requireOwner(partyCode, authentication, session);
        libraryService.delete(party.getPartyCode());
        return "redirect:" + UriComponentsBuilder.fromPath("/dj/settings").queryParam("party", party.getPartyCode())
                .fragment("libraryCard").build().toUriString();
    }

    private ResponseEntity<String> answer(HttpStatus status, String code, Locale locale, Object... args) {
        Object[] all = args.length > 0 ? args : new Object[]{DjLibraryService.MAX_TRACKS, maxBytes / (1024 * 1024)};
        return ResponseEntity.status(status).contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8))
                .body(messageSource.getMessage(code, all, locale));
    }

    /** The file read past the most bytes allowed. */
    private static final class TooLarge extends IOException {
    }

    /** The request's body, but never more than {@code max} bytes of it: past them, {@link TooLarge}. */
    private static final class Bounded extends FilterInputStream {

        private final long max;
        private long read;

        Bounded(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(long n) throws TooLarge {
            read += n;
            if (read > max) {
                throw new TooLarge();
            }
        }
    }
}
