package com.scan2play.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.VapidKeyGenerator;
import com.scan2play.entity.PushSubscriptionEntity;
import com.scan2play.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PushNotificationService}: what is sent to each of the DJ's devices, in its language, and what happens when a push
 * service says a device is gone. The keys are made here (no secret in the repo); the HTTP client is a mock.
 */
class PushNotificationServiceTest {

    private static final String OWNER = "dj-1";
    private static final String PARTY = "PSH01";

    private static String serverPublicKey;
    private static String serverPrivateKey;
    private static String deviceP256dh;
    private static final String DEVICE_AUTH = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);

    private final PushSubscriptionRepository repository = mock(PushSubscriptionRepository.class);
    private final HttpClient httpClient = mock(HttpClient.class);
    private final ResourceBundleMessageSource messages = new ResourceBundleMessageSource();

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair server = generator.generateKeyPair();
        serverPublicKey = VapidKeyGenerator.publicKeyBase64Url((ECPublicKey) server.getPublic());
        serverPrivateKey = Base64.getEncoder().encodeToString(server.getPrivate().getEncoded());
        deviceP256dh = VapidKeyGenerator.publicKeyBase64Url((ECPublicKey) generator.generateKeyPair().getPublic());
    }

    private PushNotificationService service(String publicKey, String privateKey) {
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        // An executor that runs at once: the test sees what was sent when notifyNewRequest returns
        return new PushNotificationService(repository, messages, publicKey, privateKey, "https://www.scan2play.com.pl/",
                httpClient, Runnable::run);
    }

    private static PushSubscriptionEntity device(String endpoint, String locale) {
        return PushSubscriptionEntity.builder().ownerId(OWNER).endpoint(endpoint).p256dh(deviceP256dh).auth(DEVICE_AUTH)
                .locale(locale).createdAt(Instant.now()).build();
    }

    @SuppressWarnings("unchecked")
    private void pushServiceAnswers(int status) throws Exception {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);
    }

    @Test
    void withoutKeys_notificationsAreOff_andNothingIsSent() {
        PushNotificationService off = service("", "");

        off.notifyNewRequest(OWNER, PARTY, "Wilki - Baśka");

        assertThat(off.isEnabled()).isFalse();
        assertThat(off.publicKey()).isNull();
        verifyNoInteractions(repository, httpClient);
    }

    @Test
    void keysThatAreNotValid_turnNotificationsOff_insteadOfStoppingTheApp() {
        PushNotificationService off = service("not-a-key", "bm90IGEga2V5");

        assertThat(off.isEnabled()).isFalse();
    }

    @Test
    void keysAsTheyArePasted_areRead_whateverTheCopyBroughtAlong() {
        // base64 with its "=" padding, the variable's name in front, quotes, a line break in the middle (a terminal's wrap)
        String paddedPrivate = Base64.getEncoder().encodeToString(Base64.getDecoder().decode(serverPrivateKey));
        String messyPrivate = "\"VAPID_PRIVATE_KEY=" + paddedPrivate.substring(0, 40) + "\n" + paddedPrivate.substring(40) + "\" ";
        String paddedPublic = " " + serverPublicKey + "=\t";

        PushNotificationService push = service(paddedPublic, messyPrivate.replace("\"VAPID_PRIVATE_KEY=", "VAPID_PRIVATE_KEY=\""));

        assertThat(push.isEnabled()).isTrue();
        assertThat(push.publicKey()).as("one spelling for the browser").isEqualTo(serverPublicKey);
    }

    @Test
    void aKeyWithACharacterLost_isNamedInTheLog_andTheNotificationsAreOff() {
        assertThat(PushNotificationService.keyBytes("VAPID_PRIVATE_KEY", serverPrivateKey.substring(1), 0)).isNull();
        assertThat(PushNotificationService.keyBytes("VAPID_PUBLIC_KEY", serverPublicKey.substring(4), 65)).as("too short").isNull();
        assertThat(service(serverPublicKey, serverPrivateKey.substring(1)).isEnabled()).isFalse();
    }

    @Test
    void theTwoKeys_mustBeOnePair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        String otherPublic = VapidKeyGenerator.publicKeyBase64Url((ECPublicKey) generator.generateKeyPair().getPublic());

        // zerodep checks the pair when it is made: a public key of another pair is refused
        assertThat(service(otherPublic, serverPrivateKey).isEnabled()).isFalse();
    }

    @Test
    void everyDeviceOfTheDj_getsAnEncryptedMessage_atItsPushService() throws Exception {
        when(repository.findTop20ByOwnerIdOrderByCreatedAtDesc(OWNER)).thenReturn(List.of(
                device("https://fcm.googleapis.com/fcm/send/abc", "pl"),
                device("https://web.push.apple.com/QGx", "en")));
        pushServiceAnswers(201);
        PushNotificationService push = service(serverPublicKey, serverPrivateKey);

        push.notifyNewRequest(OWNER, PARTY, "Wilki - Baśka");

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, org.mockito.Mockito.times(2)).send(sent.capture(), any());
        HttpRequest first = sent.getAllValues().get(0);
        assertThat(first.uri().toString()).isEqualTo("https://fcm.googleapis.com/fcm/send/abc");
        assertThat(first.method()).isEqualTo("POST");
        assertThat(first.headers().firstValue("Content-Encoding")).hasValue("aes128gcm");
        assertThat(first.headers().firstValue("Urgency")).hasValue("high");
        assertThat(first.headers().firstValue("TTL")).hasValue(String.valueOf(PushNotificationService.TTL.toSeconds()));
        assertThat(first.headers().firstValue("Authorization")).get().asString().startsWith("vapid t=").contains("k=" + serverPublicKey);
        assertThat(sent.getAllValues().get(1).uri().getHost()).isEqualTo("web.push.apple.com");
        verify(repository, never()).deleteByEndpoint(anyString());
    }

    @Test
    void theMessage_isInTheDevicesLanguage_withTheSongAndWhereATapLeads() throws Exception {
        PushNotificationService push = service(serverPublicKey, serverPrivateKey);
        ObjectMapper json = new ObjectMapper();

        JsonNode pl = json.readTree(push.payload(device("https://fcm.googleapis.com/x", "pl"), PARTY, "Wilki - Baśka"));
        JsonNode en = json.readTree(push.payload(device("https://fcm.googleapis.com/y", null), PARTY, "Wilki - Baśka"));

        assertThat(pl.path("title").asText()).isEqualTo("🎵 Nowa prośba");
        assertThat(pl.path("titleMany").asText()).as("the service worker puts the count in").isEqualTo("🎵 Nowe prośby: {0}");
        assertThat(pl.path("body").asText()).isEqualTo("Wilki - Baśka");
        assertThat(pl.path("tag").asText()).isEqualTo("s2p-" + PARTY);
        assertThat(pl.path("url").asText()).isEqualTo("/dj/dashboard");
        assertThat(en.path("title").asText()).isEqualTo("🎵 New request");
    }

    @Test
    void aDeviceThePushServiceNoLongerKnows_isForgotten() throws Exception {
        when(repository.findTop20ByOwnerIdOrderByCreatedAtDesc(OWNER)).thenReturn(List.of(device("https://fcm.googleapis.com/gone", "pl")));
        pushServiceAnswers(410);

        service(serverPublicKey, serverPrivateKey).notifyNewRequest(OWNER, PARTY, "X - Y");

        verify(repository).deleteByEndpoint("https://fcm.googleapis.com/gone");
    }

    @Test
    void aPushServiceThatFails_isLogged_andTheNextDeviceStillGetsIt() throws Exception {
        when(repository.findTop20ByOwnerIdOrderByCreatedAtDesc(OWNER)).thenReturn(List.of(
                device("https://fcm.googleapis.com/a", "pl"), device("https://fcm.googleapis.com/b", "pl")));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("connection reset"));

        service(serverPublicKey, serverPrivateKey).notifyNewRequest(OWNER, PARTY, "X - Y");

        verify(httpClient, org.mockito.Mockito.times(2)).send(any(), any());
        verify(repository, never()).deleteByEndpoint(anyString());
    }
}
