package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import org.springframework.context.MessageSource;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Locale;

/**
 * Under "the DJ takes songs here" (a mood or a message, not a song) on the party page and the kiosk: where the guest's words can go —
 * a dedication with a song ({@code guest.error.song_only.hint}). Only while the DJ takes dedications: the hint names their field. A wish
 * ("sto lat dla Ani") reaches the DJ that way, checked by the AI as every dedication — there is no message to the DJ of its own.
 */
final class SongOnlyHint {

    private SongOnlyHint() {
    }

    static void add(RedirectAttributes redirectAttributes, PartySettingsEntity settings, Locale locale, MessageSource messageSource) {
        if (settings.isDedicationsOn()) {
            redirectAttributes.addFlashAttribute(ViewAttributes.ERROR_HINT,
                    messageSource.getMessage("guest.error.song_only.hint", null, locale));
        }
    }
}
