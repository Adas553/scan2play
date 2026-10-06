package com.scan2play.repository;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.PushSubscriptionEntity;
import com.scan2play.service.PushSubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DJ's devices of the notifications on a real PostgreSQL (V21): one row per browser (the endpoint is UNIQUE), a browser switched
 * on again by another DJ moves to them, and the deletes work outside a transaction of the caller (the sending thread of
 * PushNotificationService removes a device the push service no longer knows).
 */
class PushSubscriptionRepositoryIT extends PostgresIntegrationTest {

    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/it-device";
    private static final String P256DH;
    private static final String AUTH = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);

    static {
        byte[] point = new byte[65];
        point[0] = 4;
        P256DH = Base64.getUrlEncoder().withoutPadding().encodeToString(point);
    }

    @Autowired PushSubscriptionRepository repository;
    @Autowired PushSubscriptionService service;

    @BeforeEach
    void empty() {
        repository.deleteAll();
    }

    @Test
    void aBrowserIsOneRow_andMovesToTheDjWhoSwitchesItOnThere() {
        service.subscribe("dj-a", ENDPOINT, P256DH, AUTH, Locale.forLanguageTag("pl"));
        service.subscribe("dj-b", ENDPOINT, P256DH, AUTH, Locale.ENGLISH);

        assertThat(repository.count()).isOne();
        assertThat(repository.findByEndpoint(ENDPOINT)).get().satisfies(device -> {
            assertThat(device.getOwnerId()).isEqualTo("dj-b");
            assertThat(device.getLocale()).isEqualTo("en");
        });
        assertThat(repository.findTop20ByOwnerIdOrderByCreatedAtDesc("dj-a")).isEmpty();
    }

    @Test
    void theEndpointIsUnique_inTheDatabaseToo() {
        repository.saveAndFlush(device("dj-a", ENDPOINT));

        assertThatThrownBy(() -> repository.saveAndFlush(device("dj-b", ENDPOINT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDeletes_workWithoutTheCallersTransaction_andOnlyOnTheirOwnRows() {
        repository.save(device("dj-a", ENDPOINT));
        repository.save(device("dj-a", ENDPOINT + "-2"));
        repository.save(device("dj-b", ENDPOINT + "-3"));

        assertThat(repository.deleteByOwnerIdAndEndpoint("dj-b", ENDPOINT)).as("not dj-b's device").isZero();
        assertThat(repository.deleteByEndpoint(ENDPOINT)).isOne();
        assertThat(repository.deleteByOwnerId("dj-a")).isOne();
        assertThat(repository.findAll()).extracting(PushSubscriptionEntity::getOwnerId).containsExactly("dj-b");
    }

    private static PushSubscriptionEntity device(String owner, String endpoint) {
        return PushSubscriptionEntity.builder().ownerId(owner).endpoint(endpoint).p256dh(P256DH).auth(AUTH)
                .createdAt(Instant.now()).build();
    }
}
