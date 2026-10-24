package com.scan2play.service;

import com.scan2play.repository.PartySettingsRepository;
import com.scan2play.repository.SongRequestDedicationRepository;
import com.scan2play.repository.SongRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.AdditionalAnswers.returnsFirstArg;

/**
 * The demo's reset and the guests' daily limit (the owner, 2026-10-24): the limit of a party (300 per 24 h) is one for every visitor
 * of a demo, so a script that spent it blocked the demo's guest page until a restart or the next day — the nightly reset put the
 * examples back but not the limit. The rows themselves: {@code DemoPartyIT}.
 */
class DemoPartyTest {

    @Test
    void theReset_startsTheDemosDailyLimitAgain_andNoOtherPartys() {
        GuestRequestLimiter limiter = new GuestRequestLimiter(0, 10, 3, "");
        for (String code : new String[]{DemoParty.CODE, DemoParty.CODE_EN, "AB12C"}) {
            for (int i = 0; i < 3; i++) {
                limiter.tryAcquire("10.0.0." + i, code);
            }
            assertThat(limiter.isPartyLimitReached(code)).as(code).isTrue();
        }
        PartySettingsRepository parties = mock(PartySettingsRepository.class);
        when(parties.findByPartyCode(any())).thenReturn(Optional.empty());
        when(parties.save(any())).then(returnsFirstArg());
        SongRequestRepository requests = mock(SongRequestRepository.class);
        when(requests.save(any())).then(returnsFirstArg());

        new DemoParty(parties, requests, mock(SongRequestDedicationRepository.class), new ConcurrentMapCacheManager(),
                mock(PlatformTransactionManager.class), limiter).reset();

        assertThat(limiter.partyRequestsUsed(DemoParty.CODE)).isZero();
        assertThat(limiter.partyRequestsUsed(DemoParty.CODE_EN)).isZero();
        assertThat(limiter.isPartyLimitReached("AB12C")).as("a real party keeps its day").isTrue();
    }
}
