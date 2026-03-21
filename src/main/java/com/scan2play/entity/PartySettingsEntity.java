package com.scan2play.entity;

import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import com.scan2play.util.CodeGenerator;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
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
public class PartySettingsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 5)
    private String partyCode;

    // The unique ID of the DJ (from OAuth2 provider, e.g., Spotify ID)
    @Column(nullable = false, unique = true)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    private VibeType globalVibe;

    @Enumerated(EnumType.STRING)
    private MusicProviderType activeProvider = MusicProviderType.SPOTIFY;

    @Enumerated(EnumType.STRING)
    private PlaybackMode playbackMode = PlaybackMode.MANUAL;

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
