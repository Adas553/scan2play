package com.scan2play.controller;

import com.scan2play.service.AdminReportService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The owner's report by hand: theirs alone (a 404 for anyone else, and for everyone while unset); the preview and the send. */
class AdminReportControllerTest {

    private static final String OWNER = "107402337416904056873";

    private final AdminReportService service = mock(AdminReportService.class);

    private static OAuth2AuthenticationToken login(String sub) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(List.of(), Map.of("sub", sub), "sub"), List.of(), "google");
    }

    @Test
    void theOwner_seesTheReportAsItWouldGo_andSendsIt() {
        when(service.preview()).thenReturn(new AdminReportService.Report("Scan2Play — raport za 7.11", "RUCH …", "RUCH (bez adresów)", null));
        when(service.address()).thenReturn("owner@example.com");
        when(service.canSend()).thenReturn(true);
        when(service.sendNow()).thenReturn(true, false);
        AdminReportController controller = new AdminReportController(service, OWNER);
        ConcurrentModel model = new ConcurrentModel();

        assertThat(controller.report(null, null, login(OWNER), model)).isEqualTo("stats-report");
        assertThat(model.getAttribute("text")).isEqualTo("RUCH …");
        assertThat(model.getAttribute("address")).isEqualTo("owner@example.com");
        assertThat(model.getAttribute("canSend")).isEqualTo(true);
        assertThat(model.getAttribute("weekDays")).asList().hasSize(7);
        assertThat(controller.send(login(OWNER))).isEqualTo("redirect:/dj/stats/report?sent=yes");
        assertThat(controller.send(login(OWNER))).isEqualTo("redirect:/dj/stats/report?sent=no");
    }

    @Test
    void theSchedule_isSaved_aTimeThatIsNotOneIsA400() {
        AdminReportController controller = new AdminReportController(service, OWNER);

        assertThat(controller.schedule(true, " 07:45 ", List.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.SATURDAY), login(OWNER)))
                .isEqualTo("redirect:/dj/stats/report?saved=yes");
        org.mockito.Mockito.verify(service).saveSchedule(new AdminReportService.Schedule(true, java.time.LocalTime.of(7, 45),
                java.util.Set.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.SATURDAY)));
        controller.schedule(false, "08:00", null, login(OWNER));
        org.mockito.Mockito.verify(service).saveSchedule(new AdminReportService.Schedule(false, java.time.LocalTime.of(8, 0),
                java.util.Set.of()));
        assertThatThrownBy(() -> controller.schedule(true, "25:99", null, login(OWNER)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> controller.schedule(true, "08:00", null, login("someone-else"))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anyoneElse_andEveryoneWhileUnset_getA404() {
        AdminReportController controller = new AdminReportController(service, OWNER);
        assertThatThrownBy(() -> controller.report(null, null, login("someone-else"), new ConcurrentModel()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.send(null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> new AdminReportController(service, "").send(login(OWNER))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}
