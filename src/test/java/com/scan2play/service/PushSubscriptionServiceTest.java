package com.scan2play.service;

import com.scan2play.entity.PushSubscriptionEntity;
import com.scan2play.repository.PushSubscriptionRepository;
import com.scan2play.service.PushSubscriptionService.InvalidSubscriptionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PushSubscriptionService}: only a push service's address is kept (the server sends to it), the keys must have the
 * right length, a DJ keeps a bounded number of devices, and a DJ can only switch off their own.
 */
class PushSubscriptionServiceTest {

    private static final String P256DH = Base64.getUrlEncoder().withoutPadding().encodeToString(point());
    private static final String AUTH = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    private static final String FCM = "https://fcm.googleapis.com/fcm/send/abc:def";

    private final PushSubscriptionRepository repository = mock(PushSubscriptionRepository.class);
    private final PushSubscriptionService service = new PushSubscriptionService(repository);

    private static byte[] point() {
        byte[] point = new byte[65];
        point[0] = 4;
        return point;
    }

    @Test
    void theBrowsersPushServices_areTaken() {
        assertThat(PushSubscriptionService.isPushServiceEndpoint(FCM)).isTrue();
        assertThat(PushSubscriptionService.isPushServiceEndpoint("https://web.push.apple.com/QGxyz")).isTrue();
        assertThat(PushSubscriptionService.isPushServiceEndpoint("https://updates.push.services.mozilla.com/wpush/v2/gAAA")).isTrue();
        assertThat(PushSubscriptionService.isPushServiceEndpoint("https://wns2-db5p.notify.windows.com/w/?token=x")).isTrue();
    }

    @Test
    void anyOtherAddress_isRefused_soTheServerSendsNowhereElse() {
        assertThat(PushSubscriptionService.isPushServiceEndpoint("http://fcm.googleapis.com/fcm/send/x")).as("not https").isFalse();
        assertThat(PushSubscriptionService.isPushServiceEndpoint("https://fcm.googleapis.com.evil.example/x")).isFalse();
        assertThat(PushSubscriptionService.isPushServiceEndpoint("https://evilfcm.googleapis.com/x")).as("not a subdomain").isFalse();
        assertThat(PushSubscriptionService.isPushServiceEndpoint("https://fcm.googleapis.com:8443/x")).as("another port").isFalse();
        assertThat(PushSubscriptionService.isPushServiceEndpoint("https://user@fcm.googleapis.com/x")).isFalse();
        assertThat(PushSubscriptionService.isPushServiceEndpoint("https://169.254.169.254/latest/meta-data")).isFalse();
        assertThat(PushSubscriptionService.isPushServiceEndpoint("https://localhost/x")).isFalse();
        assertThat(PushSubscriptionService.isPushServiceEndpoint("https://fcm.googleapis.com/" + "x".repeat(1000))).as("too long").isFalse();
        assertThat(PushSubscriptionService.isPushServiceEndpoint(null)).isFalse();
    }

    @Test
    void keysOfTheWrongLength_areRefused() {
        assertThatThrownBy(() -> service.subscribe("dj-1", FCM, AUTH, AUTH, Locale.ENGLISH))
                .isInstanceOf(InvalidSubscriptionException.class);
        assertThatThrownBy(() -> service.subscribe("dj-1", FCM, P256DH, "not base64!", Locale.ENGLISH))
                .isInstanceOf(InvalidSubscriptionException.class);
        assertThatThrownBy(() -> service.subscribe("dj-1", "https://example.com/push", P256DH, AUTH, Locale.ENGLISH))
                .isInstanceOf(InvalidSubscriptionException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void aNewDevice_isKept_withTheDashboardsLanguage() {
        when(repository.findByEndpoint(FCM)).thenReturn(Optional.empty());
        ArgumentCaptor<PushSubscriptionEntity> saved = ArgumentCaptor.forClass(PushSubscriptionEntity.class);

        service.subscribe("dj-1", FCM, P256DH, AUTH, Locale.forLanguageTag("pl"));

        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOwnerId()).isEqualTo("dj-1");
        assertThat(saved.getValue().getEndpoint()).isEqualTo(FCM);
        assertThat(saved.getValue().getLocale()).isEqualTo("pl");
        assertThat(saved.getValue().getCreatedAt()).isNotNull();
    }

    @Test
    void aBrowserSwitchedOnAgain_byAnotherDj_becomesTheirs_notASecondRow() {
        PushSubscriptionEntity existing = PushSubscriptionEntity.builder().id(3L).ownerId("dj-old").endpoint(FCM)
                .p256dh(P256DH).auth(AUTH).createdAt(Instant.EPOCH).build();
        when(repository.findByEndpoint(FCM)).thenReturn(Optional.of(existing));

        service.subscribe("dj-new", FCM, P256DH, AUTH, Locale.ENGLISH);

        verify(repository).save(existing);
        assertThat(existing.getOwnerId()).isEqualTo("dj-new");
    }

    @Test
    void theOldestDevices_goBeyondTheLimit() {
        when(repository.findByEndpoint(FCM)).thenReturn(Optional.empty());
        List<PushSubscriptionEntity> devices = new ArrayList<>(IntStream.range(0, PushSubscriptionService.MAX_DEVICES + 2)
                .mapToObj(i -> PushSubscriptionEntity.builder().id((long) i).ownerId("dj-1").build()).toList());
        when(repository.findTop20ByOwnerIdOrderByCreatedAtDesc("dj-1")).thenReturn(devices);

        service.subscribe("dj-1", FCM, P256DH, AUTH, Locale.ENGLISH);

        verify(repository).deleteAll(devices.subList(PushSubscriptionService.MAX_DEVICES, devices.size()));
    }

    @Test
    void switchingOff_removesOnlyTheDjsOwnDevice() {
        service.unsubscribe("dj-1", FCM);

        verify(repository).deleteByOwnerIdAndEndpoint("dj-1", FCM);
        verify(repository, never()).deleteByEndpoint(any());
    }
}
