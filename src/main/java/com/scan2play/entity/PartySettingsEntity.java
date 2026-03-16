package com.scan2play.entity;

import com.scan2play.model.MusicProviderType;
import com.scan2play.model.PlaybackMode;
import com.scan2play.model.VibeType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "party_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartySettingsEntity {

    @Id
    private Long id; // We will always use ID = 1, as we manage one party at a time

    @Enumerated(EnumType.STRING)
    private VibeType globalVibe;

    @Enumerated(EnumType.STRING)
    private MusicProviderType activeProvider = MusicProviderType.SPOTIFY;

    @Enumerated(EnumType.STRING)
    private PlaybackMode playbackMode = PlaybackMode.MANUAL;

    // --- Spotify OAuth2 Credentials ---
    @Column(length = 2048) // Tokens can be long
    private String spotifyAccessToken;

    @Column(length = 2048)
    private String spotifyRefreshToken;

    private LocalDateTime spotifyTokenExpiresAt;
}
