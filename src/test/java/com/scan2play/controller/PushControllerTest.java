package com.scan2play.controller;

import com.scan2play.service.PushSubscriptionService;
import com.scan2play.service.PushSubscriptionService.InvalidSubscriptionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link PushController}: the browser's {@code PushSubscription.toJSON()} reaches the service for the logged-in DJ; a
 * refused one is a 400.
 */
class PushControllerTest {

    private static final String SUBSCRIPTION = """
            {"endpoint":"https://fcm.googleapis.com/fcm/send/abc","expirationTime":null,
             "keys":{"p256dh":"BKey","auth":"auth1"}}""";

    private final PushSubscriptionService service = mock(PushSubscriptionService.class);
    private MockMvc mockMvc;
    private final OAuth2AuthenticationToken dj = new OAuth2AuthenticationToken(
            new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "dj-1"), "sub"),
            AuthorityUtils.createAuthorityList("ROLE_USER"), "google");

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PushController(service)).build();
    }

    @Test
    void theBrowsersSubscription_isKeptForTheLoggedInDj() throws Exception {
        mockMvc.perform(post("/dj/push/subscribe").principal(dj).contentType(MediaType.APPLICATION_JSON).content(SUBSCRIPTION))
                .andExpect(status().isNoContent());

        verify(service).subscribe(eq("dj-1"), eq("https://fcm.googleapis.com/fcm/send/abc"), eq("BKey"), eq("auth1"), any());
    }

    @Test
    void aRefusedSubscription_isABadRequest() throws Exception {
        doThrow(new InvalidSubscriptionException("not a push service's address"))
                .when(service).subscribe(any(), any(), any(), any(), any());

        mockMvc.perform(post("/dj/push/subscribe").principal(dj).contentType(MediaType.APPLICATION_JSON).content(SUBSCRIPTION))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutKeys_isABadRequest() throws Exception {
        mockMvc.perform(post("/dj/push/subscribe").principal(dj).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://fcm.googleapis.com/x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void switchingOff_forgetsTheDevice() throws Exception {
        mockMvc.perform(post("/dj/push/unsubscribe").principal(dj).contentType(MediaType.APPLICATION_JSON).content(SUBSCRIPTION))
                .andExpect(status().isNoContent());

        verify(service).unsubscribe("dj-1", "https://fcm.googleapis.com/fcm/send/abc");
    }
}
