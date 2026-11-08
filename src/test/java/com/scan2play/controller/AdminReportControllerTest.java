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
        when(service.preview()).thenReturn(new AdminReportService.Report("Scan2Play — raport za 7.11", "RUCH …", "RUCH (bez adresów)"));
        when(service.address()).thenReturn("owner@example.com");
        when(service.canSend()).thenReturn(true);
        when(service.sendNow()).thenReturn(true, false);
        AdminReportController controller = new AdminReportController(service, OWNER);
        ConcurrentModel model = new ConcurrentModel();

        assertThat(controller.report(null, login(OWNER), model)).isEqualTo("stats-report");
        assertThat(model.getAttribute("text")).isEqualTo("RUCH …");
        assertThat(model.getAttribute("address")).isEqualTo("owner@example.com");
        assertThat(model.getAttribute("canSend")).isEqualTo(true);
        assertThat(controller.send(login(OWNER))).isEqualTo("redirect:/dj/stats/report?sent=yes");
        assertThat(controller.send(login(OWNER))).isEqualTo("redirect:/dj/stats/report?sent=no");
    }

    @Test
    void anyoneElse_andEveryoneWhileUnset_getA404() {
        AdminReportController controller = new AdminReportController(service, OWNER);
        assertThatThrownBy(() -> controller.report(null, login("someone-else"), new ConcurrentModel()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.send(null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> new AdminReportController(service, "").send(login(OWNER))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}
