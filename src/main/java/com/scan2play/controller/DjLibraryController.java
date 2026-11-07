package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.PlanFeature;
import com.scan2play.service.DjLibraryParser;
import com.scan2play.service.DjLibraryParser.Library;
import com.scan2play.service.DjLibraryParser.UnreadableLibraryException;
import com.scan2play.service.DjLibraryService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PlanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * "Twoja biblioteka" in the settings (V41, plan Pro): the organiser's rekordbox collection or M3U playlist, read into the songs' keys
 * ({@link DjLibraryParser}) so the queue marks each request "✓", "?" or "✗". The organiser's alone — the staff work with it, never
 * change it.
 * <p>
 * The file comes as the request's body ({@code application/octet-stream}, library.js), not as a form: a form's upload is read by
 * the server to find the CSRF token before anything checks who sent it, so the size allowed for a library would be anyone's to
 * send. A body is read only here, after the login, the CSRF token and the plan, and only up to {@link #MAX_BYTES}. One file is read at
 * a time on the whole server: reading one holds up to a hundred megabytes, and the heap is half a gigabyte; a DJ's real collection is
 * read in seconds, so a second upload waits for it ({@link #WAIT_SECONDS}) and only then hears "try again".
 */
@Controller
@RequestMapping("/dj/library")
public class DjLibraryController {

    /** The largest file: a rekordbox collection of {@value DjLibraryService#MAX_TRACKS} tracks is about 50 MB. */
    static final long MAX_BYTES = 64L * 1024 * 1024;

    /** How long an upload waits for the one being read before it is refused (503). */
    static final int WAIT_SECONDS = 30;

    /**
     * The longest a file may take to arrive and be read, its turn held all the while: 64 MB in 5 minutes is 1.7 Mbit/s, and a DJ's
     * usual collection of a few MB arrives in seconds even on a phone's network. Without it a body sent a byte at a time (each within
     * the server's read timeout) would hold the one turn for hours and every other DJ would hear "try again in a moment".
     */
    static final Duration READ_LIMIT = Duration.ofMinutes(5);

    private final Semaphore reading = new Semaphore(1);

    private final DjLibraryService libraryService;
    private final DjSessionHelper sessionHelper;
    private final PlanService planService;
    private final MessageSource messageSource;
    private final PartySettingsCommandService partySettingsCommandService;
    private final long maxBytes;
    private final Duration readLimit;

    @Autowired
    public DjLibraryController(DjLibraryService libraryService, DjSessionHelper sessionHelper, PlanService planService,
                               MessageSource messageSource, PartySettingsCommandService partySettingsCommandService,
                               @Value("${scan2play.library.max-bytes:" + MAX_BYTES + "}") long maxBytes) {
        this(libraryService, sessionHelper, planService, messageSource, partySettingsCommandService, maxBytes, READ_LIMIT);
    }

    DjLibraryController(DjLibraryService libraryService, DjSessionHelper sessionHelper, PlanService planService,
                        MessageSource messageSource, PartySettingsCommandService partySettingsCommandService, long maxBytes,
                        Duration readLimit) {
        this.libraryService = libraryService;
        this.sessionHelper = sessionHelper;
        this.planService = planService;
        this.messageSource = messageSource;
        this.partySettingsCommandService = partySettingsCommandService;
        this.maxBytes = maxBytes;
        this.readLimit = readLimit;
    }

    /**
     * The party's library is now the file — of a rekordbox collection, one {@code playlist} of it when named ("Kluby / Piątek",
     * library.js lists them; the {@code occurrence}th of that name, as two may share one): the answer is one sentence for the page —
     * how many tracks, or why not.
     */
    @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseBody
    public ResponseEntity<String> upload(@RequestParam String partyCode, @RequestParam(required = false) String playlist,
                                         @RequestParam(defaultValue = "1") int occurrence, HttpServletRequest request, Locale locale,
                                         OAuth2AuthenticationToken authentication, HttpSession session) throws IOException {
        PartySettingsEntity party = planService.require(sessionHelper.requireOwner(partyCode, authentication, session),
                PlanFeature.LIBRARY);
        if (request.getContentLengthLong() > maxBytes) {
            return answer(HttpStatus.PAYLOAD_TOO_LARGE, "settings.library.error.TOO_LARGE", locale);
        }
        Library library;
        try {
            if (!reading.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) {
                return answer(HttpStatus.SERVICE_UNAVAILABLE, "settings.library.error.BUSY", locale);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return answer(HttpStatus.SERVICE_UNAVAILABLE, "settings.library.error.BUSY", locale);
        }
        try (InputStream in = new Bounded(request.getInputStream(), maxBytes, System.nanoTime() + readLimit.toNanos())) {
            library = DjLibraryParser.parse(in, DjLibraryService.MAX_TRACKS, DjLibraryParser.MAX_COLLECTION, playlist, occurrence);
        } catch (TooLarge e) {
            return answer(HttpStatus.PAYLOAD_TOO_LARGE, "settings.library.error.TOO_LARGE", locale);
        } catch (TooSlow e) {
            return answer(HttpStatus.REQUEST_TIMEOUT, "settings.library.error.TOO_SLOW", locale);
        } catch (UnreadableLibraryException e) {
            return answer(HttpStatus.UNPROCESSABLE_ENTITY, "settings.library.error." + e.reason(), locale);
        } finally {
            reading.release();
        }
        libraryService.replace(party.getPartyCode(), library);
        return answer(HttpStatus.OK, "settings.library.uploaded", locale, library.tracks());
    }

    /**
     * "Podpowiedzi gości" (V58): whether the guests' suggestions are marked by the library, "✓ DJ ma" first. Saved as soon as picked
     * (cards-in-place.js follows the redirect and takes the choice's part of the card from the page, "✓ Zapisane" in it). Switching
     * it on needs the plan; switching it off never does.
     */
    @PostMapping("/hints")
    public String hints(@RequestParam String partyCode, @RequestParam boolean on, OAuth2AuthenticationToken authentication,
                        HttpSession session, RedirectAttributes redirect) {
        PartySettingsEntity party = sessionHelper.requireOwner(partyCode, authentication, session);
        if (on) {
            planService.require(party, PlanFeature.LIBRARY);
        }
        partySettingsCommandService.updateSettings(party.getPartyCode(), settings -> settings.setLibraryHints(on));
        redirect.addFlashAttribute(ViewAttributes.LIBRARY_HINTS_SAVED, true);
        return toTheCard(party);
    }

    /** "Usuń bibliotekę": the organiser's own data goes whatever the plan. */
    @PostMapping("/delete")
    public String delete(@RequestParam String partyCode, OAuth2AuthenticationToken authentication, HttpSession session) {
        PartySettingsEntity party = sessionHelper.requireOwner(partyCode, authentication, session);
        libraryService.delete(party.getPartyCode());
        return toTheCard(party);
    }

    /** Back to the library's card of "Ustawienia imprezy". */
    private static String toTheCard(PartySettingsEntity party) {
        return "redirect:" + UriComponentsBuilder.fromPath("/dj/settings").queryParam("party", party.getPartyCode())
                .fragment("libraryCard").build().toUriString();
    }

    private ResponseEntity<String> answer(HttpStatus status, String code, Locale locale, Object... args) {
        Object[] all = args.length > 0 ? args
                : new Object[]{DjLibraryService.MAX_TRACKS, maxBytes / (1024 * 1024), DjLibraryParser.MAX_COLLECTION,
                        readLimit.toMinutes()};
        return ResponseEntity.status(status).contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8))
                .body(messageSource.getMessage(code, all, locale));
    }

    /** The file read past the most bytes allowed. */
    private static final class TooLarge extends IOException {
    }

    /** The file still arriving past {@link #READ_LIMIT}. */
    private static final class TooSlow extends IOException {
    }

    /**
     * The request's body, but never more than {@code max} bytes of it (past them, {@link TooLarge}) and none after {@code deadline}
     * ({@link System#nanoTime()}; past it, {@link TooSlow}).
     */
    private static final class Bounded extends FilterInputStream {

        private final long max;
        private final long deadline;
        private long read;

        Bounded(InputStream in, long max, long deadline) {
            super(in);
            this.max = max;
            this.deadline = deadline;
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

        private void count(long n) throws IOException {
            read += n;
            if (read > max) {
                throw new TooLarge();
            }
            if (System.nanoTime() - deadline > 0) {
                throw new TooSlow();
            }
        }
    }
}
