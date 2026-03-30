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

import java.time.LocalDateTime;

@Entity
@Table(name = "party_settings", indexes = {
    @Index(name = "idx_owner_id", columnList = "ownerId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartySettingsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 5)
    private String partyCode;

    // The unique ID of the DJ (from OAuth2 provider, e.g., Spotify ID)
    @Column(nullable = false, unique = true)
    private String ownerId;

    @Column(nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    private VibeType globalVibe;

    @Enumerated(EnumType.STRING)
    private MusicProviderType activeProvider = MusicProviderType.SPOTIFY;

    @Enumerated(EnumType.STRING)
    private PlaybackMode playbackMode = PlaybackMode.MANUAL;

    // --- Rate Limiting ---
    @Column(nullable = false)
    private int requestLimit = 2;

    @Column(nullable = false)
    private int cooldownMinutes = 3;

    // --- Duplicate Filtering ---
    @Column(nullable = false)
    private int duplicateCheckWindow = 15;

    // --- Spotify OAuth2 Credentials ---
    @Column(length = 2048)
    private String spotifyAccessToken;

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
