package com.scan2play.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One of the DJ's browsers that takes notifications of new requests (Web Push, V21): the push service's address of that browser
 * and its keys. The DJ switches it on per device ("🔔 Powiadomienia na tym urządzeniu"); the row goes when it is switched off, when
 * the push service says the address is gone, or with the account.
 */
@Entity
@Table(name = "push_subscription", indexes = {
    @Index(name = "idx_push_subscription_owner_id", columnList = "ownerId")
})   // endpoint is UNIQUE (its own index)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushSubscriptionEntity {

    /** The longest push service address kept (the column, V21); the services' own are 200–500 characters. */
    public static final int ENDPOINT_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The DJ (the Google subject), as {@link PartySettingsEntity#getOwnerId()}. */
    @Column(nullable = false)
    private String ownerId;

    @Column(nullable = false, unique = true, length = ENDPOINT_MAX)
    private String endpoint;

    /** The browser's public key for the message's encryption, base64url. */
    @Column(nullable = false, length = 200)
    private String p256dh;

    /** The browser's authentication secret, base64url. */
    @Column(nullable = false, length = 100)
    private String auth;

    /** The language of the dashboard that switched it on (a language tag, e.g. "pl"): the notification's words. */
    @Column(length = 20)
    private String locale;

    @Column(nullable = false)
    private Instant createdAt;
}
