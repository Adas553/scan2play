package com.scan2play.controller;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.service.GuestRequestLimiter;
import com.scan2play.service.PlanService;
import com.scan2play.service.Plans;
import com.scan2play.service.WishService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The guests' page of "Życzenia przed imprezą" (V68) with the plans enforced and the limits as they answer: a party without Pro is a
 * 404 — the page and the form, nothing written —, and a wish over the address's limit is said, not kept. The rest through the real
 * security: {@link WishWebTest}.
 */
class WishControllerTest {

    private static final String TOKEN = "Wish_123-xyzWish_123-xy";
    private static final String OWNER = "wish-owner";

    private final WishService wishes = mock(WishService.class);
    private final PartySettingsRepository parties = mock(PartySettingsRepository.class);
    private final GuestRequestLimiter limiter = mock(GuestRequestLimiter.class);
    private final StaticMessageSource messages = new StaticMessageSource();

    @BeforeEach
    void setUp() {
        messages.setUseCodeAsDefaultMessage(true);
        when(wishes.byToken(TOKEN)).thenReturn(Optional.of(new WishService.Event(1, "WSH01", TOKEN, "Wesele", WishService.today().plusDays(9),
                false, 0, Instant.now(), com.scan2play.model.GuestTheme.CLASSIC)));
        when(parties.findByPartyCode("WSH01")).thenReturn(Optional.of(PartySettingsEntity.builder().partyCode("WSH01").ownerId(OWNER).build()));
        when(limiter.clientIp(org.mockito.ArgumentMatchers.any())).thenReturn("203.0.113.7");
        when(limiter.tryAcquireWish(anyString(), anyString())).thenReturn(Optional.empty());
        when(wishes.add(anyString(), anyString())).thenReturn(WishService.Added.ADDED);
    }

    private WishController controller(PlanService plans) {
        return new WishController(wishes, parties, plans, limiter, messages);
    }

    @Test
    void aPartyWithoutPro_isA404_thePageAndTheForm_andNothingIsWritten() {
        WishController controller = controller(Plans.enforced());
        assertThatThrownBy(() -> controller.page(TOKEN, new ExtendedModelMap(), new MockHttpSession()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.write(TOKEN, "Wilki - Baśka", new MockHttpSession(), new MockHttpServletRequest(),
                new RedirectAttributesModelMap()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(wishes, never()).add(anyString(), anyString());
        verify(limiter, never()).tryAcquireWish(anyString(), anyString());

        assertThat(controller(Plans.enforced(OWNER)).page(TOKEN, new ExtendedModelMap(), new MockHttpSession())).as("with Pro").isEqualTo("wish");
    }

    @Test
    void aWishOverTheAddressesLimit_isSaid_andNotKept() {
        when(limiter.tryAcquireWish("203.0.113.7", TOKEN)).thenReturn(Optional.of(360L));
        RedirectAttributesModelMap answer = new RedirectAttributesModelMap();
        MockHttpSession session = new MockHttpSession();

        assertThat(controller(Plans.off()).write(TOKEN, "Wilki - Baśka", session, new MockHttpServletRequest(), answer))
                .isEqualTo("redirect:/w/" + TOKEN);

        assertThat(answer.getFlashAttributes().get(ViewAttributes.WISH_NOTE)).isEqualTo("wish.note.too_many");
        assertThat(answer.getFlashAttributes()).doesNotContainKey(ViewAttributes.WISH_ADDED);
        verify(wishes, never()).add(anyString(), anyString());
        assertThat(session.getAttribute(WishController.SESSION_PREFIX + 1)).as("not one of the guest's five").isNull();
    }
}
