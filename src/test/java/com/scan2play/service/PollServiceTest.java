package com.scan2play.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Pytanie do gości" (V74) without a database: the question and the answers as kept, the results' shares and leaders, the states, and
 * the voter's token — made of the session's id, not the id itself.
 */
class PollServiceTest {

    private static PollService.Poll poll(String... answers) {
        return TestPolls.asked(1, "PARTY", "Disco polo?", List.of(answers), 0);
    }

    @Test
    void theQuestion_isOneLine_cutToItsLength_andNothingIsNone() {
        assertThat(PollService.tidyQuestion("  Disco\npolo\t— tak czy nie?  ")).isEqualTo("Disco polo — tak czy nie?");
        assertThat(PollService.tidyQuestion("x".repeat(200))).hasSize(PollService.QUESTION_MAX);
        assertThat(PollService.tidyQuestion("   ")).isNull();
        assertThat(PollService.tidyQuestion(null)).isNull();
    }

    @Test
    void theAnswers_leaveOutTheEmptyAndTheRepeated_keepFour_andNeedTwo() {
        assertThat(PollService.tidyAnswers(Arrays.asList("Tak", " ", null, "tak", "Nie"))).containsExactly("Tak", "Nie");
        assertThat(PollService.tidyAnswers(List.of("A", "B", "C", "D", "E"))).containsExactly("A", "B", "C", "D");
        assertThat(PollService.tidyAnswers(List.of("y".repeat(60), "Nie")).get(0)).hasSize(PollService.ANSWER_MAX);
        assertThat(PollService.tidyAnswers(List.of("Tak", "TAK"))).as("one answer twice is one").isNull();
        assertThat(PollService.tidyAnswers(List.of("Tak"))).isNull();
        assertThat(PollService.tidyAnswers(null)).isNull();
    }

    @Test
    void theResults_givePercents_andMarkEveryLeader_butNoneWithoutVotes() {
        PollService.Results results = PollService.results(poll("Tak", "Nie", "Nie wiem"), new int[]{7, 7, 1});
        assertThat(results.total()).isEqualTo(15);
        assertThat(results.rows()).extracting(PollService.Row::percent).containsExactly(47, 47, 7);
        assertThat(results.rows()).extracting(PollService.Row::leads).containsExactly(true, true, false);

        PollService.Results none = PollService.results(poll("Tak", "Nie"), new int[]{0, 0});
        assertThat(none.total()).isZero();
        assertThat(none.rows()).extracting(PollService.Row::percent).containsExactly(0, 0);
        assertThat(none.rows()).extracting(PollService.Row::leads).containsExactly(false, false);
    }

    @Test
    void aQuestion_isSaved_asked_orEnded() {
        Instant now = Instant.now();
        assertThat(TestPolls.saved(1, "P", "Q", List.of("A", "B")).state()).isEqualTo(PollService.State.SAVED);
        assertThat(TestPolls.asked(1, "P", "Q", List.of("A", "B"), 0).asked()).isTrue();
        assertThat(TestPolls.ended(1, "P", "Q", List.of("A", "B"), 0).state()).isEqualTo(PollService.State.ENDED);
        // asked and forgotten: ended by itself ASKED_FOR after it was asked, at that moment
        Instant asked = now.minus(PollService.ASKED_FOR).minusSeconds(60);
        assertThat(PollService.endOf(asked, null, now)).isEqualTo(asked.plus(PollService.ASKED_FOR));
        assertThat(PollService.endOf(now.minusSeconds(60), null, now)).as("asked a minute ago: still asked").isNull();
        assertThat(PollService.endOf(asked, now, now)).as("the DJ's end stays").isEqualTo(now);
        assertThat(PollService.endOf(null, null, now)).as("saved for later").isNull();
    }

    @Test
    void theVoter_isTheSameForOneSession_anotherForAnother_andNotTheSessionsId() {
        MockHttpSession one = new MockHttpSession(null, "3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f");
        MockHttpSession two = new MockHttpSession(null, "9d8c7b6a-5f4e-4d3c-8b2a-0f1e2d3c4b5a");
        String voter = PollService.voterOf(one);
        assertThat(voter).hasSize(32).matches("[0-9a-f]+").isEqualTo(PollService.voterOf(one)).isNotEqualTo(PollService.voterOf(two));
        assertThat(voter).doesNotContain(one.getId());
    }
}
