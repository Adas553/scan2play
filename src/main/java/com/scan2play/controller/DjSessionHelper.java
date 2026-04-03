package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartySettingsQueryService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Shared helper for resolving the current DJ's party settings from the HTTP session.
 * Caches the partyCode in the session to avoid redundant DB lookups on every request.
 * <p>
 * Used by all DJ-facing controllers that need access to the current party context.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DjSessionHelper {

    static final String SESSION_PARTY_CODE = "djPartyCode";
    private static final String GOOGLE_REGISTRATION_ID = "google";

    private final PartySettingsQueryService partySettingsQueryService;
    private final PartySettingsCommandService partySettingsCommandService;

    /**
     * Resolves party settings using a cached partyCode from the HTTP session when available.
     * Falls back to {@code getOrCreatePartyForDj} (DB lookup by ownerId) on first access,
     * then stores the partyCode in the session for subsequent requests.
     *
     * @param authentication The OAuth2 authentication token.
     * @param session        The current HTTP session.
     * @return The PartySettingsEntity for this DJ.
     */
    public PartySettingsEntity getPartySettings(OAuth2AuthenticationToken authentication, HttpSession session) {
        String cachedPartyCode = (String) session.getAttribute(SESSION_PARTY_CODE);

        if (cachedPartyCode != null) {
            try {
                return partySettingsQueryService.getSettings(cachedPartyCode);
            } catch (IllegalArgumentException e) {
                log.warn("Cached partyCode '{}' no longer valid, falling back to ownerId lookup", cachedPartyCode);
                session.removeAttribute(SESSION_PARTY_CODE);
            }
        }

        String ownerId = authentication.getName();
        MusicProviderType provider = resolveProviderFromAuth(authentication);
        PartySettingsEntity settings = partySettingsCommandService.getOrCreatePartyForDj(ownerId, provider);
        session.setAttribute(SESSION_PARTY_CODE, settings.getPartyCode());
        return settings;
    }

    /**
     * Resolves the {@link MusicProviderType} from the OAuth2 authentication token.
     * Spotify registration maps to SPOTIFY, Google registration maps to YOUTUBE.
     */
    MusicProviderType resolveProviderFromAuth(OAuth2AuthenticationToken authentication) {
        String registrationId = authentication.getAuthorizedClientRegistrationId();
        return GOOGLE_REGISTRATION_ID.equalsIgnoreCase(registrationId)
                ? MusicProviderType.YOUTUBE
                : MusicProviderType.SPOTIFY;
    }
}

