package com.scan2play.service;

import com.scan2play.entity.PartySettingsEntity;
import com.scan2play.model.LibraryMark;
import com.scan2play.util.LibraryKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The guests' suggestions marked by the DJ's library (V58): told only for an open party whose organiser has the plan, a library and
 * the switch on; only "✓" — a "? Podobny" is no promise to a guest —, for at most ten songs at a time.
 */
class LibraryHintServiceTest {

    private static final String PARTY = "PUB01";
    private static final String OWNER = "dj-1";

    private final DjLibraryService libraries = mock(DjLibraryService.class);

    private static PartySettingsEntity party() {
        return PartySettingsEntity.builder().partyCode(PARTY).ownerId(OWNER).active(true).build();
    }

    private void withLibrary() {
        when(libraries.info(PARTY)).thenReturn(Optional.of(new DjLibraryService.Info(2, DjLibraryParser.Format.REKORDBOX, Instant.now(),
                null, true)));
    }

    @Test
    void on_onlyForAnOpenParty_withTheSwitch_aLibrary_andThePlan() {
        withLibrary();
        assertThat(new LibraryHintService(libraries, Plans.off()).on(party())).isTrue();
        assertThat(new LibraryHintService(libraries, Plans.enforced(OWNER)).on(party())).as("Pro").isTrue();
        assertThat(new LibraryHintService(libraries, Plans.enforced()).on(party())).as("no Pro").isFalse();

        PartySettingsEntity closed = party();
        closed.setActive(false);
        assertThat(new LibraryHintService(libraries, Plans.off()).on(closed)).as("an ended party").isFalse();
        PartySettingsEntity off = party();
        off.setLibraryHints(false);
        assertThat(new LibraryHintService(libraries, Plans.off()).on(off)).as("switched off").isFalse();

        when(libraries.info(PARTY)).thenReturn(Optional.empty());
        assertThat(new LibraryHintService(libraries, Plans.off()).on(party())).as("no library").isFalse();
    }

    @Test
    void have_saysHaveOnly_inTheSongsOrder_inOneLookup() {
        when(libraries.marks(eq(PARTY), anyCollection())).thenReturn(Map.of(
                LibraryKey.of("Wilki - Baśka"), LibraryMark.HAVE,
                LibraryKey.of("Lady Pank - Baśka"), LibraryMark.SIMILAR,
                LibraryKey.of("ABBA - SOS"), LibraryMark.MISSING));

        assertThat(new LibraryHintService(libraries, Plans.off()).have(PARTY,
                List.of("ABBA - SOS", "Wilki - Baśka", "Lady Pank - Baśka", "Wilki - Baśka (Radio Edit)")))
                .as("\"?\" is no promise to a guest; a version of a song the DJ has is it").containsExactly(false, true, false, true);
        verify(libraries).marks(eq(PARTY), anyCollection());
    }

    @Test
    void have_looksAtTenSongsAtMost_andNoneForNone() {
        when(libraries.marks(eq(PARTY), anyCollection())).thenReturn(Map.of());
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add("Artist " + i + " - Title " + i);
        }
        LibraryHintService hints = new LibraryHintService(libraries, Plans.off());

        assertThat(hints.have(PARTY, many)).hasSize(LibraryHintService.MOST_SONGS).containsOnly(false);
        org.mockito.ArgumentCaptor<Collection<LibraryKey>> asked = org.mockito.ArgumentCaptor.captor();
        verify(libraries).marks(eq(PARTY), asked.capture());
        assertThat(asked.getValue()).hasSize(LibraryHintService.MOST_SONGS);

        assertThat(hints.have(PARTY, List.of())).isEmpty();
        verify(libraries, never()).info(PARTY);
    }

    /**
     * A script could learn the DJ's library ten songs at a time: an address may ask {@value LibraryHintService#ASKS_A_MINUTE} times a
     * minute of one party — a room of phones behind one Wi-Fi types far less —, then the suggestions go unmarked for the rest of it.
     */
    @Test
    void anAddress_mayAskSoManyTimesAMinute_ofOneParty() {
        LibraryHintService hints = new LibraryHintService(libraries, Plans.off());
        for (int i = 0; i < LibraryHintService.ASKS_A_MINUTE; i++) {
            assertThat(hints.mayAsk(PARTY, "203.0.113.7")).as("ask %d", i + 1).isTrue();
        }
        assertThat(hints.mayAsk(PARTY, "203.0.113.7")).as("one more").isFalse();
        assertThat(hints.mayAsk(PARTY, "203.0.113.8")).as("another address").isTrue();
        assertThat(hints.mayAsk("PUB02", "203.0.113.7")).as("another party").isTrue();
    }
}
