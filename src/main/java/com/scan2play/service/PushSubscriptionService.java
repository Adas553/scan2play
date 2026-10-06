package com.scan2play.service;

import com.scan2play.entity.PushSubscriptionEntity;
import com.scan2play.repository.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * The DJ's devices that take notifications of new requests: switched on and off per browser from the dashboard.
 * <p>
 * The server sends to the address the browser gave, so only the push services' own hosts are taken
 * ({@link #isPushServiceEndpoint}) — an address of anything else (the server's own network, a stranger's site) is refused.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushSubscriptionService {

    /** The most devices one DJ keeps (a phone, a tablet, a laptop, a spare …); the oldest goes when another is added. */
    static final int MAX_DEVICES = 10;

    /**
     * The push services of the browsers: Chrome / Edge on Android and the desktop (Google FCM), Safari and every iPhone browser
     * (Apple), Firefox (Mozilla), Edge on Windows (Microsoft WNS). A host is one of them or a subdomain of one.
     */
    static final List<String> PUSH_SERVICE_HOSTS = List.of(
            "fcm.googleapis.com", "push.apple.com", "push.services.mozilla.com", "notify.windows.com");

    /** p256dh: an uncompressed P-256 point; auth: 16 random bytes (RFC 8291). */
    private static final int P256DH_BYTES = 65;
    private static final int AUTH_BYTES = 16;

    private final PushSubscriptionRepository repository;

    /** What the browser gave did not pass {@link #subscribe}'s checks. */
    public static class InvalidSubscriptionException extends RuntimeException {
        public InvalidSubscriptionException(String message) {
            super(message);
        }
    }

    /**
     * Keeps the device of {@code ownerId}. A browser that already has a row (switched on again, or by another DJ who logged in on
     * the same browser) gets its keys and its owner refreshed.
     *
     * @throws InvalidSubscriptionException when the address is not a push service's or a key is malformed
     */
    @Transactional
    public void subscribe(String ownerId, String endpoint, String p256dh, String auth, Locale locale) {
        if (!isPushServiceEndpoint(endpoint)) {
            throw new InvalidSubscriptionException("not a push service's address");
        }
        if (!isBase64Url(p256dh, P256DH_BYTES) || !isBase64Url(auth, AUTH_BYTES)) {
            throw new InvalidSubscriptionException("malformed keys");
        }
        PushSubscriptionEntity subscription = repository.findByEndpoint(endpoint)
                .orElseGet(() -> PushSubscriptionEntity.builder().endpoint(endpoint).build());
        subscription.setOwnerId(ownerId);
        subscription.setP256dh(p256dh);
        subscription.setAuth(auth);
        subscription.setLocale(locale == null ? null : locale.toLanguageTag());
        subscription.setCreatedAt(Instant.now());
        repository.save(subscription);

        List<PushSubscriptionEntity> devices = repository.findTop20ByOwnerIdOrderByCreatedAtDesc(ownerId);
        if (devices.size() > MAX_DEVICES) {
            repository.deleteAll(devices.subList(MAX_DEVICES, devices.size()));
        }
        log.info("Push notifications on for DJ {} ({} device(s))", ownerId, Math.min(devices.size(), MAX_DEVICES));
    }

    /** Switched off on that device. Only the DJ's own row goes: the address comes from the browser. */
    @Transactional
    public void unsubscribe(String ownerId, String endpoint) {
        if (endpoint != null && repository.deleteByOwnerIdAndEndpoint(ownerId, endpoint) > 0) {
            log.info("Push notifications off on a device of DJ {}", ownerId);
        }
    }

    /** An https address of one of {@link #PUSH_SERVICE_HOSTS}, on the default port, no user info, not longer than the column. */
    static boolean isPushServiceEndpoint(String endpoint) {
        if (endpoint == null || endpoint.length() > PushSubscriptionEntity.ENDPOINT_MAX) {
            return false;
        }
        try {
            URI uri = new URI(endpoint);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return false;
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            return PUSH_SERVICE_HOSTS.stream().anyMatch(service -> lowerHost.equals(service) || lowerHost.endsWith("." + service));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** Base64url (padded or not) that decodes to exactly {@code bytes} bytes. */
    static boolean isBase64Url(String value, int bytes) {
        if (value == null || value.length() > 2 * bytes) {
            return false;
        }
        try {
            return Base64.getUrlDecoder().decode(value).length == bytes;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
