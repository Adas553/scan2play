package com.scan2play.controller;

import com.scan2play.service.DjLibraryService;
import com.scan2play.service.DjService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PlanService;
import com.scan2play.service.WishService;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Życzenia przed imprezą" (V68) for the endpoint-wide tests ({@link StaffPermissionEndpointsTest}, {@link PlanFeatureEndpointsTest}):
 * a {@link WishService} whose party has the event {@value #EVENT}, and the real {@link DjWishesController} over it.
 */
final class WishControllers {

    /** The id of the party's one event. */
    static final long EVENT = 1;

    private WishControllers() {
    }

    static WishService wishes(String partyCode) {
        WishService wishes = mock(WishService.class);
        WishService.Event event = new WishService.Event(EVENT, partyCode, "wish-token", "Wesele Ani i Kuby",
                WishService.today().plusDays(30), false, 0, Instant.now(), com.scan2play.model.GuestTheme.CLASSIC);
        when(wishes.event(partyCode, EVENT)).thenReturn(Optional.of(event));
        when(wishes.eventsOf(partyCode)).thenReturn(List.of(event));
        when(wishes.create(anyString(), anyString(), any(), any())).thenReturn(Optional.of(event));
        when(wishes.close(partyCode, EVENT)).thenReturn(true);
        when(wishes.reopen(partyCode, EVENT)).thenReturn(WishService.Reopened.REOPENED);
        when(wishes.newLink(partyCode, EVENT)).thenReturn(true);
        when(wishes.setTheme(org.mockito.ArgumentMatchers.eq(partyCode), org.mockito.ArgumentMatchers.eq(EVENT), any())).thenReturn(true);
        when(wishes.delete(partyCode, EVENT)).thenReturn(true);
        when(wishes.wishes(anyLong())).thenReturn(List.of());
        when(wishes.wishes(anyLong(), anyInt())).thenReturn(List.of());
        return wishes;
    }

    static DjWishesController djWishes(WishService wishes, DjSessionHelper helper, PlanService plans,
                                       PartySettingsCommandService settings, MessageSource messages) {
        DjWishesController controller = new DjWishesController(wishes, helper, plans, mock(DjService.class), mock(DjLibraryService.class),
                settings, messages);
        ReflectionTestUtils.setField(controller, "rawBaseUrl", "http://localhost:8080");
        return controller;
    }
}
