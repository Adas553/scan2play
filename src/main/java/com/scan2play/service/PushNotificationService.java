package com.scan2play.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scan2play.entity.PushSubscriptionEntity;
import com.scan2play.repository.PushSubscriptionRepository;
import com.zerodeplibs.webpush.PushSubscription;
import com.zerodeplibs.webpush.VAPIDKeyPair;
import com.zerodeplibs.webpush.VAPIDKeyPairs;
import com.zerodeplibs.webpush.httpclient.StandardHttpClientRequestPreparer;
import com.zerodeplibs.webpush.key.PrivateKeySources;
import com.zerodeplibs.webpush.key.PublicKeySources;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Tells the DJ's devices that a new song is on their list (Web Push, RFC 8030 / 8291 / 8292): one encrypted message to each device
 * the DJ switched the notifications on for ({@link PushSubscriptionService}), through its browser's push service.
 * <p>
 * Sent off the guest's request thread, by a small pool of its own: the guest does not wait for Google or Apple, and a slow push
 * service cannot take the guests' threads. Delivery is best effort (the push services promise nothing) — the dashboard stays the
 * truth, a notification only says "look". The service worker ({@code static/sw.js}) shows it, and folds the notifications of one
 * party into one ("Nowe prośby: 3").
 * <p>
 * Without the VAPID keys ({@code push.vapid.*}) nothing is sent and the dashboard does not offer the switch.
 */
@Slf4j
@Service
public class PushNotificationService {

    /** A request older than this is not news any more: the push service drops it instead of delivering it late. */
    static final Duration TTL = Duration.ofHours(1);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final int SEND_THREADS = 2;
    /** Notifications waiting to be sent; more are dropped (logged) rather than held in memory. */
    private static final int SEND_QUEUE = 200;

    /** A P-256 public key as an uncompressed point: 0x04, x, y. */
    private static final int PUBLIC_KEY_BYTES = 65;

    /** Where a tap on the notification leads. */
    static final String DASHBOARD_URL = "/dj/dashboard";

    private final PushSubscriptionRepository repository;
    private final MessageSource messageSource;
    private final HttpClient httpClient;
    private final Executor sender;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** Null when the keys are not set (or not valid): notifications are off. */
    private final VAPIDKeyPair keyPair;
    private final String publicKey;
    private final String subject;

    @Autowired
    public PushNotificationService(PushSubscriptionRepository repository, MessageSource messageSource,
                                   @Value("${push.vapid.public-key:}") String publicKey,
                                   @Value("${push.vapid.private-key:}") String privateKey,
                                   @Value("${push.vapid.subject:https://www.scan2play.com.pl/}") String subject) {
        this(repository, messageSource, publicKey, privateKey, subject,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                new ThreadPoolExecutor(SEND_THREADS, SEND_THREADS, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(SEND_QUEUE),
                        Thread.ofPlatform().name("push-", 1).daemon().factory()));
    }

    /** For the tests: their own client and an executor that runs at once. */
    PushNotificationService(PushSubscriptionRepository repository, MessageSource messageSource, String publicKey,
                            String privateKey, String subject, HttpClient httpClient, Executor sender) {
        this.repository = repository;
        this.messageSource = messageSource;
        this.subject = subject;
        this.httpClient = httpClient;
        this.sender = sender;
        byte[] publicBytes = keyBytes("VAPID_PUBLIC_KEY", publicKey, PUBLIC_KEY_BYTES);
        this.keyPair = keyPair(publicBytes, keyBytes("VAPID_PRIVATE_KEY", privateKey, 0));
        // The browser's applicationServerKey in one spelling, whatever the variable held
        this.publicKey = keyPair == null ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(publicBytes);
    }

    private static VAPIDKeyPair keyPair(byte[] publicKey, byte[] privateKey) {
        if (publicKey == null || privateKey == null) {
            return null;
        }
        try {
            VAPIDKeyPair pair = VAPIDKeyPairs.of(PrivateKeySources.ofPKCS8Bytes(privateKey), PublicKeySources.ofUncompressedBytes(publicKey));
            if (!onePair(privateKey, publicKey)) {
                // The push services would refuse every message (403) — say it now, not with each request
                log.error("Push notifications off: VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY are not one pair — copy both from one run of VapidKeyGenerator");
                return null;
            }
            log.info("Push notifications on");
            return pair;
        } catch (RuntimeException e) {
            // Not a reason to keep the guests' requests from working: the switch is just not offered
            log.error("Push notifications off: VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY are not a key pair ({})", e.getMessage());
            return null;
        }
    }

    /** Whether the public key verifies what the private key signs (zerodep does not check that the two belong together). */
    private static boolean onePair(byte[] privateKey, byte[] publicKey) {
        try {
            KeyFactory keys = KeyFactory.getInstance("EC");
            PrivateKey signer = keys.generatePrivate(new PKCS8EncodedKeySpec(privateKey));
            ECPublicKey verifier = PublicKeySources.ofUncompressedBytes(publicKey).extract();
            byte[] probe = "scan2play-vapid-pair".getBytes(StandardCharsets.UTF_8);
            Signature sign = Signature.getInstance("SHA256withECDSA");
            sign.initSign(signer);
            sign.update(probe);
            byte[] signature = sign.sign();
            Signature verify = Signature.getInstance("SHA256withECDSA");
            verify.initVerify(verifier);
            verify.update(probe);
            return verify.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * The bytes of a key as it was pasted into a variable: base64 or base64url, with or without the padding "=", and forgiving what
     * a copy brings along — spaces and line breaks, quotes around it, the variable's own name in front ("VAPID_PUBLIC_KEY=…").
     * Null (logged, without the key itself) when it is not set or does not read as {@code expectedBytes} bytes; 0 = any length.
     */
    static byte[] keyBytes(String name, String value, int expectedBytes) {
        if (value == null || value.isBlank()) {
            log.info("Push notifications off: {} not set", name);
            return null;
        }
        String text = value.strip();
        if (text.startsWith(name + "=")) {
            text = text.substring(name.length() + 1);
        }
        text = text.replaceAll("[\\s\"']", "").replace('+', '-').replace('/', '_');
        while (text.endsWith("=")) {
            text = text.substring(0, text.length() - 1);
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(text);
            if (expectedBytes > 0 && bytes.length != expectedBytes) {
                log.error("Push notifications off: {} is {} bytes, not {} — copy the whole line VapidKeyGenerator printed",
                        name, bytes.length, expectedBytes);
                return null;
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            log.error("Push notifications off: {} is not base64 ({} characters: a character lost or added in the copy?)",
                    name, text.length());
            return null;
        }
    }

    /** Whether the keys are set: the dashboard offers the switch only then. */
    public boolean isEnabled() {
        return keyPair != null;
    }

    /** The browser's {@code applicationServerKey} (base64url), or null when notifications are off. */
    public String publicKey() {
        return publicKey;
    }

    /**
     * A new song is on the DJ's list (not a vote on one that waits): every device of the DJ gets "🎵 Nowa prośba — <song>". Returns
     * at once; the sending happens in the background.
     */
    public void notifyNewRequest(String ownerId, String partyCode, String songName) {
        if (keyPair == null || ownerId == null) {
            return;
        }
        try {
            sender.execute(() -> sendToDevices(ownerId, partyCode, songName));
        } catch (RejectedExecutionException e) {
            log.warn("Push notification for party {} dropped: the queue is full", partyCode);
        }
    }

    void sendToDevices(String ownerId, String partyCode, String songName) {
        try {
            List<PushSubscriptionEntity> devices = repository.findTop20ByOwnerIdOrderByCreatedAtDesc(ownerId);
            for (PushSubscriptionEntity device : devices) {
                send(device, payload(device, partyCode, songName));
            }
        } catch (RuntimeException e) {
            log.warn("Push notifications for party {} not sent: {}", partyCode, e.toString());
        }
    }

    /** What the service worker shows: the texts in the device's language, the song, how to fold and where a tap leads. */
    String payload(PushSubscriptionEntity device, String partyCode, String songName) {
        Locale locale = device.getLocale() == null ? Locale.ENGLISH : Locale.forLanguageTag(device.getLocale());
        Map<String, String> message = new LinkedHashMap<>();
        message.put("title", messageSource.getMessage("push.new_request.title", null, locale));
        // "{0}" stays in the text: the service worker puts the count in (no arguments → no MessageFormat here)
        message.put("titleMany", messageSource.getMessage("push.new_requests.title", null, locale));
        message.put("body", songName);
        message.put("tag", "s2p-" + partyCode);
        message.put("url", DASHBOARD_URL);
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void send(PushSubscriptionEntity device, String payload) {
        PushSubscription subscription = new PushSubscription();
        subscription.setEndpoint(device.getEndpoint());
        PushSubscription.Keys keys = new PushSubscription.Keys();
        keys.setP256dh(device.getP256dh());
        keys.setAuth(device.getAuth());
        subscription.setKeys(keys);
        try {
            HttpRequest request = StandardHttpClientRequestPreparer.getBuilder()
                    .pushSubscription(subscription)
                    .pushMessage(payload)
                    .ttl(TTL.toSeconds(), TimeUnit.SECONDS)
                    .urgencyHigh()
                    .vapidJWTSubject(subject)
                    .vapidJWTExpiresAfter(12, TimeUnit.HOURS)
                    .build(keyPair)
                    .toRequestBuilder()
                    .timeout(SEND_TIMEOUT)
                    .build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 404 || status == 410) {
                // The browser dropped it (the notifications were blocked, the site's data cleared, the app removed)
                repository.deleteByEndpoint(device.getEndpoint());
                log.info("Push subscription of DJ {} gone ({}): removed", device.getOwnerId(), status);
            } else if (status >= 300) {
                log.warn("Push service answered {} for a device of DJ {}", status, device.getOwnerId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            log.warn("Push notification to a device of DJ {} failed: {}", device.getOwnerId(), e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        if (sender instanceof ExecutorService executor) {
            executor.shutdown();
        }
    }
}
