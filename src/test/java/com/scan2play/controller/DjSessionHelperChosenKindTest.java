package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PartySettingsQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The kind of party a DJ chose on the landing page (HomeController.start keeps it in the session before Google's login): a new DJ
 * gets a party of that kind; a DJ who has one gets the same party — the same code — of the chosen kind; the choice counts once,
 * and only for a Google login.
 */
class DjSessionHelperChosenKindTest {

    private static final String OWNER = "google-owner-1";

    private final PartySettingsQueryService query = mock(PartySettingsQueryService.class);
    private final PartySettingsCommandService command = mock(PartySettingsCommandService.class);
    private final DjSessionHelper helper = new DjSessionHelper(query, command);
    private final MockHttpSession session = new MockHttpSession();

    private static OAuth2AuthenticationToken login(String registration) {
        OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
        when(token.getName()).thenReturn(OWNER);
        when(token.getAuthorizedClientRegistrationId()).thenReturn(registration);
        return token;
    }

    private static PartySettingsEntity party(MusicProviderType kind, PlaybackMode mode) {
        return PartySettingsEntity.builder().ownerId(OWNER).partyCode("ABC12").activeProvider(kind).playbackMode(mode).build();
    }

    /** updateSettings applies the updater to the party it was given and returns it (as the real one returns the saved entity). */
    @SuppressWarnings("unchecked")
    private void updatesApply(PartySettingsEntity party) {
        when(command.updateSettings(eq("ABC12"), any())).thenAnswer(call -> {
            ((Consumer<PartySettingsEntity>) call.getArgument(1)).accept(party);
            return party;
        });
    }

    @Test
    void aNewDj_whoChoseTheRequestsTile_getsARequestsOnlyParty() {
        session.setAttribute(DjSessionHelper.SESSION_CHOSEN_PROVIDER, "REQUESTS_ONLY");
        PartySettingsEntity created = party(MusicProviderType.REQUESTS_ONLY, PlaybackMode.MANUAL);
        when(command.getOrCreatePartyForDj(OWNER, MusicProviderType.REQUESTS_ONLY)).thenReturn(created);

        assertThat(helper.getPartySettings(login("google"), session).getActiveProvider()).isEqualTo(MusicProviderType.REQUESTS_ONLY);
        assertThat(session.getAttribute(DjSessionHelper.SESSION_CHOSEN_PROVIDER)).as("used once").isNull();
    }

    @Test
    void aDjWithAYouTubeParty_whoChoseTheRequestsTile_getsTheSamePartyAsRequestsOnly_withAutoPilotOff() {
        session.setAttribute(DjSessionHelper.SESSION_CHOSEN_PROVIDER, "REQUESTS_ONLY");
        PartySettingsEntity existing = party(MusicProviderType.YOUTUBE, PlaybackMode.AUTO);
        when(command.getOrCreatePartyForDj(eq(OWNER), any())).thenReturn(existing);
        updatesApply(existing);

        PartySettingsEntity settings = helper.getPartySettings(login("google"), session);

        assertThat(settings.getPartyCode()).isEqualTo("ABC12");
        assertThat(settings.getActiveProvider()).isEqualTo(MusicProviderType.REQUESTS_ONLY);
        assertThat(settings.getPlaybackMode()).isEqualTo(PlaybackMode.MANUAL);
    }

    @Test
    void aDjAlreadyLoggedIn_whoChoseTheYouTubeTile_getsTheirPartyBackAsYouTube() {
        session.setAttribute(DjSessionHelper.SESSION_PARTY_CODE, "ABC12");
        session.setAttribute(DjSessionHelper.SESSION_CHOSEN_PROVIDER, "YOUTUBE");
        PartySettingsEntity existing = party(MusicProviderType.REQUESTS_ONLY, PlaybackMode.MANUAL);
        when(query.getSettings("ABC12")).thenReturn(existing);
        updatesApply(existing);

        assertThat(helper.getPartySettings(login("google"), session).getActiveProvider()).isEqualTo(MusicProviderType.YOUTUBE);
    }

    @Test
    void withoutAChoice_thePartyKeepsItsKind() {
        session.setAttribute(DjSessionHelper.SESSION_PARTY_CODE, "ABC12");
        when(query.getSettings("ABC12")).thenReturn(party(MusicProviderType.REQUESTS_ONLY, PlaybackMode.MANUAL));

        assertThat(helper.getPartySettings(login("google"), session).getActiveProvider()).isEqualTo(MusicProviderType.REQUESTS_ONLY);
        verify(command, never()).updateSettings(anyString(), any());
    }

    @Test
    void aSpotifyLogin_ignoresAChoiceLeftInTheSession() {
        session.setAttribute(DjSessionHelper.SESSION_CHOSEN_PROVIDER, "REQUESTS_ONLY");
        when(command.getOrCreatePartyForDj(OWNER, MusicProviderType.SPOTIFY))
                .thenReturn(party(MusicProviderType.SPOTIFY, PlaybackMode.MANUAL));

        assertThat(helper.getPartySettings(login("spotify"), session).getActiveProvider()).isEqualTo(MusicProviderType.SPOTIFY);
        verify(command, never()).updateSettings(anyString(), any());
        assertThat(session.getAttribute(DjSessionHelper.SESSION_CHOSEN_PROVIDER)).isNull();
    }

    @Test
    void anUnknownChoice_isIgnored() {
        session.setAttribute(DjSessionHelper.SESSION_CHOSEN_PROVIDER, "VINYL");
        when(command.getOrCreatePartyForDj(OWNER, MusicProviderType.YOUTUBE))
                .thenReturn(party(MusicProviderType.YOUTUBE, PlaybackMode.MANUAL));

        assertThat(helper.getPartySettings(login("google"), session).getActiveProvider()).isEqualTo(MusicProviderType.YOUTUBE);
        assertThat(Optional.ofNullable(session.getAttribute(DjSessionHelper.SESSION_CHOSEN_PROVIDER))).isEmpty();
    }
}
