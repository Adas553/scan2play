package com.scan2play.service;

import com.scan2play.util.CodeGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key of a party's lock of the guests' requests ({@link SongRequestCommandService#lockKey}): one party, one key — two parties
 * never take turns on one lock. The code's hash gave "AOXXX" and "B0XXX" the same key (review 2026-10-14: a 32-bit hash).
 */
class RequestsLockKeyTest {

    @Test
    void twoCodesWithTheSameHash_haveTheirOwnKeys() {
        assertThat("AOXXX".hashCode()).as("the hash collides").isEqualTo("B0XXX".hashCode());

        assertThat(SongRequestCommandService.lockKey("AOXXX")).isNotEqualTo(SongRequestCommandService.lockKey("B0XXX"));
    }

    @Test
    void everyCodeTheAppMakes_hasAKeyOfItsOwn_inTheRequestsSpace() {
        Map<Long, String> seen = new HashMap<>();
        for (int i = 0; i < 200_000; i++) {
            String code = CodeGenerator.generatePartyCode();
            long key = SongRequestCommandService.lockKey(code);
            String before = seen.putIfAbsent(key, code);
            assertThat(before == null || before.equals(code)).as(code + " and " + before).isTrue();
            assertThat(key >>> 32).as("\"S2PR\" in the upper half").isEqualTo(0x5332_5052L);
        }
        assertThat(SongRequestCommandService.lockKey("ABC12")).isEqualTo(SongRequestCommandService.lockKey("ABC12"));
    }

    @Test
    void aCodeOfAnotherShape_stillHasAKey_apartFromTheMadeOnes() {
        long other = SongRequestCommandService.lockKey("ab-c1");

        assertThat(other >>> 32).isEqualTo(0x5332_5052L);
        assertThat(other & 0x8000_0000L).as("the hashed half of the space").isNotZero();
        assertThat(SongRequestCommandService.lockKey("ZZZZZ") & 0x8000_0000L).as("the packed half").isZero();
    }
}
