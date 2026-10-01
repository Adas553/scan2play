package com.scan2play.entity;

import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import com.scan2play.util.CodeGenerator;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@Entity
@Table(name = "party_settings")   // ownerId and partyCode are UNIQUE (their own indexes)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)   // toBuilder: PartySettingsQueryService hands out copies of the cached settings
public class PartySettingsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 5)
    private String partyCode;

    // The unique ID of the DJ (from OAuth2 provider, e.g., Spotify ID)
    @Column(nullable = false, unique = true)
    private String ownerId;

    // NOTE: every field with an initializer needs @Builder.Default — without it Lombok's
    // @Builder silently ignores the initializer and PartySettingsEntity.builder().build()
    // yields 0 / false / null instead of the defaults below.
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    private VibeType globalVibe;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private MusicProviderType activeProvider = MusicProviderType.SPOTIFY;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private PlaybackMode playbackMode = PlaybackMode.MANUAL;

    // --- Rate Limiting ---
    @Builder.Default
    @Column(nullable = false)
    private int requestLimit = 2;

    @Builder.Default
    @Column(nullable = false)
    private int cooldownMinutes = 3;

    // --- Duplicate Filtering ---
    @Builder.Default
    @Column(nullable = false)
    private int duplicateCheckWindow = 15;

    // --- YouTube Fallback Playlist ---
    /** YouTube playlist URL played automatically when the guest queue is empty (YouTube provider only). */
    @Column(length = 500)
    private String fallbackPlaylistUrl;

    /** Whether the fallback playlist should play in shuffled order. Default: true. */
    @Builder.Default
    @Column(nullable = false)
    private boolean fallbackShuffle = true;

    // --- Spotify OAuth2 Credentials --- (never in toString: one logged entity would put them in the logs)
    @ToString.Exclude
    @Column(length = 2048)
    private String spotifyAccessToken;

    @ToString.Exclude
    @Column(length = 2048)
    private String spotifyRefreshToken;

    private LocalDateTime spotifyTokenExpiresAt;

    @PrePersist
    public void generateCode() {
        if (this.partyCode == null || this.partyCode.isEmpty()) {
            this.partyCode = CodeGenerator.generatePartyCode();
        }
        if (this.globalVibe == null) {
            this.globalVibe = VibeType.ANY;
        }
    }
}
