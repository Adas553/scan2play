package com.scan2play.controller;

import com.scan2play.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A DJ's first login, opened in several tabs at once ({@link DjSessionHelper#getPartySettings}): each tab finds no party and
 * creates one, and all but one of them hit the UNIQUE owner_id. Every tab must still get the DJ's one party — not an error page.
 */
class FirstLoginIT extends PostgresIntegrationTest {

    private static final int TABS = 8;
    private static final int ROUNDS = 10;

    @Autowired DjSessionHelper sessionHelper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aFirstLoginInSeveralTabsAtOnce_givesEveryTabTheDjsOneParty() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(TABS);
        try {
            // a few rounds, each a new DJ: one round alone may happen to run in turns
            for (int round = 0; round < ROUNDS; round++) {
                String ownerId = "first-login-" + UUID.randomUUID();
                OAuth2AuthenticationToken dj = token(ownerId);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> tabs = new ArrayList<>();
                for (int i = 0; i < TABS; i++) {
                    tabs.add(pool.submit(() -> {
                        start.await();
                        return sessionHelper.getPartySettings(dj, new MockHttpSession()).getPartyCode();
                    }));
                }
                start.countDown();
                List<String> codes = new ArrayList<>();
                for (Future<String> tab : tabs) {
                    codes.add(tab.get(30, TimeUnit.SECONDS));   // an exception here is the error page of that tab
                }
                assertThat(codes).as("round " + round).containsOnly(codes.getFirst());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM party_settings WHERE owner_id = ?", Integer.class, ownerId))
                        .isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static OAuth2AuthenticationToken token(String ownerId) {
        DefaultOAuth2User user = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("sub", ownerId, "name", "DJ"), "sub");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
    }
}
