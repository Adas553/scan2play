package com.scan2play.controller;

import com.scan2play.service.PollService;
import com.scan2play.service.TestPolls;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Pytanie do gości" (V74) for the endpoint-wide tests ({@link StaffPermissionEndpointsTest}, {@link PlanFeatureEndpointsTest}): a
 * {@link PollService} whose party has the question {@value #POLL}, asked now.
 */
final class PollControllers {

    /** The id of the party's one question. */
    static final long POLL = 1;

    private PollControllers() {
    }

    static PollService polls(String partyCode) {
        PollService polls = mock(PollService.class);
        PollService.Poll poll = TestPolls.asked(POLL, partyCode, "Disco polo?", List.of("Tak", "Nie"), 3);
        when(polls.poll(partyCode, POLL)).thenReturn(Optional.of(poll));
        when(polls.pollsOf(partyCode)).thenReturn(List.of(poll));
        when(polls.results(any(PollService.Poll.class))).thenAnswer(call -> new PollService.Results(call.getArgument(0), List.of(), 0));
        when(polls.resultsOf(anyList())).thenAnswer(call -> call.<List<PollService.Poll>>getArgument(0).stream()
                .map(one -> new PollService.Results(one, List.of(), 0)).toList());
        when(polls.create(anyString(), anyString(), anyList(), anyBoolean(), anyBoolean())).thenReturn(Optional.of(poll));
        when(polls.ask(partyCode, POLL)).thenReturn(true);
        when(polls.end(partyCode, POLL)).thenReturn(true);
        when(polls.setOnTv(org.mockito.ArgumentMatchers.eq(partyCode), org.mockito.ArgumentMatchers.eq(POLL), anyBoolean())).thenReturn(true);
        when(polls.setResultsHidden(org.mockito.ArgumentMatchers.eq(partyCode), org.mockito.ArgumentMatchers.eq(POLL), anyBoolean()))
                .thenReturn(true);
        when(polls.delete(partyCode, POLL)).thenReturn(true);
        when(polls.edit(org.mockito.ArgumentMatchers.eq(partyCode), org.mockito.ArgumentMatchers.eq(POLL), anyString(), anyList(),
                anyBoolean())).thenReturn(PollService.Edited.EDITED);
        return polls;
    }
}
