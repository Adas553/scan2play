package com.scan2play.controller;

import com.scan2play.service.PushSubscriptionService;
import com.scan2play.service.PushSubscriptionService.InvalidSubscriptionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The switch "🔔 Powiadomienia na tym urządzeniu" of the dashboard ({@code js/dashboard/push.js}): the browser's push subscription
 * (its {@code PushSubscription.toJSON()}) kept for the logged-in DJ, or forgotten.
 */
@Slf4j
@RestController
@RequestMapping("/dj/push")
@RequiredArgsConstructor
public class PushController {

    private final PushSubscriptionService pushSubscriptionService;

    /** What the browser's {@code PushSubscription.toJSON()} gives; {@code expirationTime} and the rest are ignored. */
    public record SubscriptionJson(String endpoint, Keys keys) {
        public record Keys(String p256dh, String auth) {
        }
    }

    @PostMapping("/subscribe")
    public ResponseEntity<Void> subscribe(@RequestBody SubscriptionJson subscription, OAuth2AuthenticationToken authentication) {
        if (subscription == null || subscription.keys() == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            pushSubscriptionService.subscribe(authentication.getName(), subscription.endpoint(), subscription.keys().p256dh(),
                    subscription.keys().auth(), LocaleContextHolder.getLocale());
            return ResponseEntity.noContent().build();
        } catch (InvalidSubscriptionException e) {
            log.warn("Push subscription of DJ {} refused: {}", authentication.getName(), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/unsubscribe")
    public ResponseEntity<Void> unsubscribe(@RequestBody SubscriptionJson subscription, OAuth2AuthenticationToken authentication) {
        if (subscription != null) {
            pushSubscriptionService.unsubscribe(authentication.getName(), subscription.endpoint());
        }
        return ResponseEntity.noContent().build();
    }
}
