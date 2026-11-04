package com.scan2play.service;

import com.scan2play.model.RefusalReason;
import com.scan2play.repository.EveningSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Counts a guest's request refused before the AI judged a song (V63) — the hosts' "nie grać" list, a song the DJ skipped lately, a mood
 * or a message — for "Podsumowanie wieczoru". Only a count of the party's evening and the reason: the request is never saved and
 * nothing of what the guest typed is kept. A count that fails is logged and never refuses the guest's answer; the demo counts nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EveningRefusals {

    private final EveningSummaryRepository eveningSummaryRepository;

    public void count(String partyCode, RefusalReason reason) {
        if (partyCode == null || DemoParty.isDemo(partyCode)) {
            return;
        }
        try {
            eveningSummaryRepository.countRefusal(partyCode, EveningSummaryService.eveningOf(Instant.now()), reason.name());
        } catch (RuntimeException e) {
            log.warn("Party [{}]: a refusal ({}) not counted for the evening summary", partyCode, reason, e);
        }
    }
}
