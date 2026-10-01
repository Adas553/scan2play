package com.scan2play.controller;

import com.scan2play.entity.FeedbackEntity;
import com.scan2play.repository.FeedbackRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The DJ's feedback form (review 6.2): only the party's owner can send, the text is stored trimmed with who and when, and an empty or
 * too long text (the column holds 2000 characters) is refused without touching the database.
 */
class FeedbackControllerTest {

    private final FeedbackRepository repository = mock(FeedbackRepository.class);
    private final DjSessionHelper sessionHelper = mock(DjSessionHelper.class);
    private final FeedbackController controller = new FeedbackController(repository, sessionHelper);
    private final MockHttpSession session = new MockHttpSession();
    private final OAuth2AuthenticationToken dj = mock(OAuth2AuthenticationToken.class);

    FeedbackControllerTest() {
        when(dj.getName()).thenReturn("owner-1");
    }

    @Test
    void theOwnersFeedback_isStoredTrimmed_withThePartyTheDjAndTheTime() {
        Instant before = Instant.now();

        var response = controller.submitFeedback("ABC12", "  The ⏭ button is too small  ", dj, session);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of("status", "ok"));
        ArgumentCaptor<FeedbackEntity> saved = ArgumentCaptor.forClass(FeedbackEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getMessage()).isEqualTo("The ⏭ button is too small");
        assertThat(saved.getValue().getPartyCode()).isEqualTo("ABC12");
        assertThat(saved.getValue().getOwnerId()).isEqualTo("owner-1");
        assertThat(saved.getValue().getSubmittedAt()).isBetween(before, Instant.now());
    }

    @Test
    void anEmptyOrTooLongText_isRefused_andNothingIsStored() {
        assertThat(controller.submitFeedback("ABC12", "   ", dj, session).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.submitFeedback("ABC12", "x".repeat(2001), dj, session).getStatusCode().value()).isEqualTo(400);
        verify(repository, never()).save(any());

        assertThat(controller.submitFeedback("ABC12", "x".repeat(2000), dj, session).getStatusCode().value())
                .as("exactly the column's length").isEqualTo(200);
    }

    @Test
    void someoneElsesParty_isRefused_beforeAnythingIsStored() {
        doThrow(new AccessDeniedException("You do not own party: ZZZ99"))
                .when(sessionHelper).validateOwnership(eq("ZZZ99"), any(), any());

        assertThatThrownBy(() -> controller.submitFeedback("ZZZ99", "hello", dj, session)).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).save(any());
    }
}
