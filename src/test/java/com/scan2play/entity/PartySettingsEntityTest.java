package com.scan2play.entity;

import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the field defaults of {@link PartySettingsEntity}. Lombok's {@code @Builder} ignores
 * plain field initializers unless the field is annotated with {@code @Builder.Default} — a new
 * party created through the builder (see PartySettingsCommandService#createNewParty) used to get
 * requestLimit=0, cooldownMinutes=0, duplicateCheckWindow=0 and fallbackShuffle=false.
 */
class PartySettingsEntityTest {

    @Test
    void builder_shouldApplyFieldDefaults_whenNotSetExplicitly() {
        PartySettingsEntity party = PartySettingsEntity.builder()
                .ownerId("owner")
                .partyCode("ABC12")
                .build();

        assertDefaults(party);
    }

    @Test
    void noArgsConstructor_shouldApplyFieldDefaults() {
        // JPA instantiates entities through the no-args constructor
        assertDefaults(new PartySettingsEntity());
    }

    @Test
    void builder_shouldLetExplicitValuesOverrideDefaults() {
        PartySettingsEntity party = PartySettingsEntity.builder()
                .ownerId("owner")
                .partyCode("ABC12")
                .activeProvider(MusicProviderType.YOUTUBE)
                .playbackMode(PlaybackMode.AUTO)
                .requestLimit(5)
                .cooldownMinutes(10)
                .duplicateCheckWindow(0)
                .fallbackShuffle(false)
                .active(false)
                .build();

        assertThat(party.getActiveProvider()).isEqualTo(MusicProviderType.YOUTUBE);
        assertThat(party.getPlaybackMode()).isEqualTo(PlaybackMode.AUTO);
        assertThat(party.getRequestLimit()).isEqualTo(5);
        assertThat(party.getCooldownMinutes()).isEqualTo(10);
        assertThat(party.getDuplicateCheckWindow()).isZero();
        assertThat(party.isFallbackShuffle()).isFalse();
        assertThat(party.isActive()).isFalse();
    }

    private static void assertDefaults(PartySettingsEntity party) {
        assertThat(party.isActive()).isTrue();
        assertThat(party.getActiveProvider()).isEqualTo(MusicProviderType.SPOTIFY);
        assertThat(party.getPlaybackMode()).isEqualTo(PlaybackMode.MANUAL);
        assertThat(party.getRequestLimit()).isEqualTo(2);
        assertThat(party.getCooldownMinutes()).isEqualTo(3);
        assertThat(party.getDuplicateCheckWindow()).isEqualTo(15);
        assertThat(party.isFallbackShuffle()).isTrue();
    }
}
