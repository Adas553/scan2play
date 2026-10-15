package com.scan2play.service;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.controller.HostController;
import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.repository.PartySettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A party's settings written from two places at once on a real PostgreSQL (review 2026-10-14 W8): the DJ's panel, the staff's and
 * the hosts' page {@code /h/{token}} all write through {@link PartySettingsCommandService#updateSettings}. Each test holds the party's
 * row while the writers queue up behind it, so they always meet — then lets them go.
 */
class PartySettingsWritesIT extends PostgresIntegrationTest {

    @Autowired PartySettingsCommandService settings;
    @Autowired PartySettingsRepository parties;
    @Autowired HostController hosts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private String party() {
        return parties.saveAndFlush(PartySettingsEntity.builder().partyCode(newPartyCode()).ownerId("writes-" + System.nanoTime())
                .active(true).build()).getPartyCode();
    }

    private Map<String, Object> row(String party) {
        return jdbc.queryForMap("SELECT dj_name, tip_url, active, host_token, host_blocked, host_wanted FROM party_settings"
                + " WHERE party_code = ?", party);
    }

    /** Holds the party's row in a transaction of its own while {@code whileHeld} runs, then commits. */
    private void holdingTheRow(String party, Runnable whileHeld) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM party_settings WHERE party_code = ? FOR UPDATE", Long.class, party);
            whileHeld.run();
        });
    }

    /**
     * Two writes of different fields at the same moment — the DJ names themself on the panel, a co-organiser sets the tip link — keep
     * both. Seen red without the row's lock in {@code updateSettings}: each read the row, changed its field and wrote every column
     * back, so the second write put the first one's field back as it had read it.
     */
    @Test
    void twoWritesAtOnce_keepBoth() throws Exception {
        String party = party();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> writes = new ArrayList<>();
        try {
            holdingTheRow(party, () -> {
                writes.add(pool.submit(() -> settings.updateSettings(party, s -> s.setDjName("DJ Ola"))));
                awaitLockWaiters(jdbc, 1);
                writes.add(pool.submit(() -> settings.updateSettings(party, s -> s.setTipUrl("https://tipply.pl/@ola"))));
                awaitLockWaiters(jdbc, 2);
            });
            for (Future<?> write : writes) {
                write.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(row(party)).containsEntry("dj_name", "DJ Ola").containsEntry("tip_url", "https://tipply.pl/@ola");
    }

    /**
     * The hosts save their lists while the DJ ends the party: the party stays ended (a copy read before "Zakończ" opened it again).
     */
    @Test
    void theHostsSavingWhileTheDjEndsTheParty_doNotOpenItAgain() throws Exception {
        String party = party();
        String token = "it-host-" + System.nanoTime();
        settings.updateSettings(party, s -> s.setHostToken(token));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> writes = new ArrayList<>();
        try {
            holdingTheRow(party, () -> {
                writes.add(pool.submit(() -> hosts.saveHostLists(token, "Zenek - Przez twe oczy zielone", null,
                        new RedirectAttributesModelMap())));
                awaitLockWaiters(jdbc, 1);
                writes.add(pool.submit(() -> settings.updateSettings(party, s -> s.setActive(false))));
                awaitLockWaiters(jdbc, 2);
            });
            for (Future<?> write : writes) {
                write.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(row(party)).containsEntry("active", false);
        assertThat((String) row(party).get("host_blocked")).as("and the hosts' list stays").contains("Przez twe oczy zielone");
    }

    /**
     * The DJ makes a new hosts' link while the hosts save on the old one: the old link writes nothing — not the lists, and not its
     * own token back over the new one (the token was checked before the write, outside its transaction).
     */
    @Test
    void theOldHostsLink_savingWhileTheDjMakesANewOne_writesNothing() throws Exception {
        String party = party();
        String old = "it-old-" + System.nanoTime();
        String renewed = "it-new-" + System.nanoTime();
        settings.updateSettings(party, s -> s.setHostToken(old));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        List<Future<?>> save = new ArrayList<>();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("UPDATE party_settings SET host_token = ? WHERE party_code = ?", renewed, party);   // the DJ's new link
                save.add(pool.submit(() -> hosts.saveHostLists(old, "Zenek - Przez twe oczy zielone", "Kult - Arahja",
                        new RedirectAttributesModelMap())));
                awaitLockWaiters(jdbc, 1);
            });
            assertThatThrownBy(() -> save.getFirst().get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        } finally {
            pool.shutdownNow();
        }

        assertThat(row(party)).containsEntry("host_token", renewed).containsEntry("host_blocked", null)
                .containsEntry("host_wanted", null);
    }
}
