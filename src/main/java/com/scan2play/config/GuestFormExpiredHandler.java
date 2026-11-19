package com.scan2play.config;

import com.scan2play.controller.ViewAttributes;
import com.scan2play.service.SongEvaluationService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.support.SessionFlashMapManager;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A guest's song request whose page was open longer than the session lives (30 minutes): the form's CSRF token went with the session,
 * and Spring Security refused it — the error page "403", the typed song lost (review 2026-10-14). Such a request goes back to the
 * party's page with what the guest typed — the song and the dedication — and a note to send it again; the page has a new session and token.
 * The same at the venue's kiosk (V56, {@code /k/{token}}): a request back to the kiosk's page with what was typed, a 👍 or "Następny
 * gość" back to it alone; a wish before an event (V68, {@code /w/{token}}) back to its page, saying to send it again; a form of the login
 * or of a password account (V72) back to its page — with the e-mail's link, if it came by one —, saying to try again. Every other
 * refusal is Spring Security's own.
 */
class GuestFormExpiredHandler implements AccessDeniedHandler {

    private static final Pattern GUEST_REQUEST = Pattern.compile("/p/([A-Za-z0-9]{1,10})/request");
    /** The venue's kiosk (V56): its request, its 👍 and "Następny gość" — the tablet's page back, a new session and token. */
    private static final Pattern KIOSK_FORM = Pattern.compile("/k/([A-Za-z0-9_-]{1,64})/(request|vote|next)");
    /** "Pytanie do gości" (V74): an answer — the party's page back, the question on it with a new token. */
    private static final Pattern POLL_FORM = Pattern.compile("/p/([A-Za-z0-9]{1,10})/poll/[0-9]{1,19}");
    /** "Życzenia przed imprezą" (V68): a guest's wish — the event's page back, a new session and token. */
    private static final Pattern WISH_FORM = Pattern.compile("/w/([A-Za-z0-9_-]{1,32})");
    /** The login's and the password accounts' forms (V72) and the page each comes from; nothing typed comes back (a password never). */
    private static final Map<String, String> ACCOUNT_FORMS = Map.of("/login/password", "/login", "/register", "/register",
            "/register/confirm", "/register/confirm", "/password/forgot", "/password/forgot", "/password/reset", "/password/reset");
    /** A link's secret as the e-mails make it (base64url), the only thing carried back. */
    private static final Pattern LINK_TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private final AccessDeniedHandler otherwise = new AccessDeniedHandlerImpl();
    private final SessionFlashMapManager flashes = new SessionFlashMapManager();
    private final MessageSource messages;
    private final LocaleResolver locales;

    GuestFormExpiredHandler(MessageSource messages, LocaleResolver locales) {
        this.messages = messages;
        this.locales = locales;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException refused)
            throws IOException, ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Matcher guestRequest = GUEST_REQUEST.matcher(path);
        Matcher kioskForm = KIOSK_FORM.matcher(path);
        boolean kiosk = kioskForm.matches();
        Matcher wishForm = WISH_FORM.matcher(path);
        if (refused instanceof CsrfException && "POST".equals(request.getMethod()) && wishForm.matches()) {
            FlashMap flash = new FlashMap();
            flash.put(ViewAttributes.WISH_NOTE, messages.getMessage("wish.form.expired", null, locales.resolveLocale(request)));
            flash.setTargetRequestPath("/w/" + wishForm.group(1));
            flashes.saveOutputFlashMap(flash, request, response);
            response.sendRedirect(request.getContextPath() + "/w/" + wishForm.group(1));
            return;
        }
        Matcher pollForm = POLL_FORM.matcher(path);
        if (refused instanceof CsrfException && "POST".equals(request.getMethod()) && pollForm.matches()) {
            response.sendRedirect(request.getContextPath() + "/p/" + pollForm.group(1));
            return;
        }
        String accountPage = ACCOUNT_FORMS.get(path);
        if (refused instanceof CsrfException && "POST".equals(request.getMethod()) && accountPage != null) {
            String token = request.getParameter("token");
            String page = accountPage + (token != null && LINK_TOKEN.matcher(token).matches() ? "?token=" + token : "");
            FlashMap flash = new FlashMap();
            flash.put("/login".equals(accountPage) ? "loginError" : "formError", "account.form.expired");
            flash.setTargetRequestPath(accountPage);
            flashes.saveOutputFlashMap(flash, request, response);
            response.sendRedirect(request.getContextPath() + page);
            return;
        }
        if (!(refused instanceof CsrfException) || !"POST".equals(request.getMethod()) || !(kiosk || guestRequest.matches())) {
            otherwise.handle(request, response, refused);
            return;
        }
        if (kiosk && !"request".equals(kioskForm.group(2))) {
            response.sendRedirect(request.getContextPath() + "/k/" + kioskForm.group(1));   // a 👍 or the next guest: the page again
            return;
        }
        String party = kiosk ? "/k/" + kioskForm.group(1) : "/p/" + guestRequest.group(1);
        FlashMap flash = new FlashMap();
        flash.put(ViewAttributes.ERROR_MESSAGE, messages.getMessage("guest.form.expired", null, locales.resolveLocale(request)));
        String typed = request.getParameter("songName");
        if (typed != null && !typed.isBlank()) {
            flash.put(ViewAttributes.LAST_REQUEST, SongEvaluationService.asTyped(typed));
        }
        // the dedication too ("dla Ani na 18-stkę"): the page opens its field with it (review 2026-10-21, U3)
        String dedication = SongEvaluationService.asDedication(request.getParameter("dedication"));
        if (dedication != null) {
            flash.put(ViewAttributes.LAST_DEDICATION, dedication);
        }
        flash.setTargetRequestPath(party);
        flashes.saveOutputFlashMap(flash, request, response);
        response.sendRedirect(request.getContextPath() + party);
    }
}
