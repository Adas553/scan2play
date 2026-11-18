package com.scan2play.repository;

import com.scan2play.PostgresIntegrationTest;
import com.scan2play.entity.DjAccountEntity;
import com.scan2play.service.AccountDeletionService;
import com.scan2play.service.PartySettingsCommandService;
import com.scan2play.service.PasswordAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The password accounts (V72) on a real PostgreSQL: one account an address, the links found by their hash, an address of a Google
 * account found whatever its case (by the index on {@code lower(owner_email)}) — never a password account's own party —, and the
 * account gone with the rest of its data.
 */
class DjAccountRepositoryIT extends PostgresIntegrationTest {

    @Autowired DjAccountRepository accounts;
    @Autowired PartySettingsCommandService commands;
    @Autowired AccountDeletionService deletion;

    private DjAccountEntity account(String email) {
        return DjAccountEntity.builder().email(email).passwordHash("{bcrypt}$2a$10$x").createdAt(Instant.now()).build();
    }

    private static String unique(String name) {
        return name + "." + System.nanoTime() + "@example.com";
    }

    @Test
    void oneAccountAnAddress_andTheLinksFoundByTheirHash() {
        String email = unique("ola");
        DjAccountEntity saved = accounts.saveAndFlush(account(email));
        saved.setVerifyTokenHash(PasswordAccountService.hash("secret-" + email));
        accounts.saveAndFlush(saved);

        assertThat(accounts.findByEmail(email)).get().extracting(DjAccountEntity::getId).isEqualTo(saved.getId());
        assertThat(accounts.findByVerifyTokenHash(PasswordAccountService.hash("secret-" + email))).isPresent();
        assertThat(accounts.findByResetTokenHash(PasswordAccountService.hash("secret-" + email))).isEmpty();
        assertThatThrownBy(() -> accounts.saveAndFlush(account(email))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAddressOfAGoogleAccount_isFound_inAnyCase_butNotAPasswordAccountsOwnParty() {
        String google = unique("dj");
        var party = commands.getOrCreatePartyForDj("google-" + System.nanoTime());
        commands.updateSettings(party.getPartyCode(), s -> s.setOwnerEmail(google.toUpperCase()));
        String local = unique("ola");
        DjAccountEntity account = accounts.saveAndFlush(account(local));
        var own = commands.getOrCreatePartyForDj(account.userId());
        commands.updateSettings(own.getPartyCode(), s -> s.setOwnerEmail(local));

        assertThat(accounts.googleAccountHas(google)).isTrue();
        assertThat(accounts.googleAccountHas(local)).as("its own party is no Google account").isFalse();
        assertThat(accounts.googleAccountHas(unique("nikt"))).isFalse();
    }

    @Test
    void theAccountGoes_withItsData() {
        DjAccountEntity account = accounts.saveAndFlush(account(unique("ola")));
        commands.getOrCreatePartyForDj(account.userId());

        deletion.deleteAllUserData(account.userId());

        assertThat(accounts.findById(account.getId())).isEmpty();
    }

}
