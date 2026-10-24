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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A guest's song request whose page was open longer than the session lives (30 minutes): the form's CSRF token went with the session,
 * and Spring Security refused it — the error page "403", the typed song lost (review 2026-10-14). Such a request goes back to the
 * party's page with what the guest typed — the song and the dedication — and a note to send it again; the page has a new session and token. Every
 * other refusal is Spring Security's own.
 */
class GuestFormExpiredHandler implements AccessDeniedHandler {

    private static final Pattern GUEST_REQUEST = Pattern.compile("/p/([A-Za-z0-9]{1,10})/request");

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
        Matcher guestRequest = GUEST_REQUEST.matcher(request.getRequestURI().substring(request.getContextPath().length()));
        if (!(refused instanceof CsrfException) || !"POST".equals(request.getMethod()) || !guestRequest.matches()) {
            otherwise.handle(request, response, refused);
            return;
        }
        String party = "/p/" + guestRequest.group(1);
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
