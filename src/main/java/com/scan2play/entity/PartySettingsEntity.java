package com.scan2play.entity;

import com.scan2play.model.CommentStyle;
import com.scan2play.model.VibeType;
import com.scan2play.util.CodeGenerator;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One party, owned by one DJ. Getters and setters, no {@code @Data}: an entity's equality is not "all fields equal" (Lombok's
 * equals and hashCode change with every setter and break a Set or a Hibernate collection), so it keeps Object's identity.
 */
@Entity
@Table(name = "party_settings")   // ownerId and partyCode are UNIQUE (their own indexes)
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)   // toBuilder: PartySettingsQueryService hands out copies of the cached settings
public class PartySettingsEntity {

    /** The longest vibe note (the column, V16). */
    public static final int VIBE_NOTE_MAX = 150;
    /** The longest DJ name (the column, V17). */
    public static final int DJ_NAME_MAX = 60;
    /** The longest address of a DJ's profile (V24). */
    public static final int LINK_MAX = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 5)
    private String partyCode;

    // The unique ID of the DJ (from the Google login)
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

    /**
     * The DJ's own words about the vibe (V16): one line of at most {@value #VIBE_NOTE_MAX} characters, e.g. "wesele 40+, bez rapu".
     * The AI is given it with every request, the guests see it on the party page. {@code null} when the DJ wrote nothing.
     */
    @Column(length = VIBE_NOTE_MAX)
    private String vibeNote;

    /** Who plays, as the DJ wrote it (V17): one line of at most {@value #DJ_NAME_MAX} characters, e.g. "DJ Koko"; null = not shown. */
    @Column(length = DJ_NAME_MAX)
    private String djName;

    /**
     * The DJ's profiles (V24), for the guests to follow: an https address on Instagram / Facebook / TikTok as
     * {@code util.SocialLinks} wrote it from what the DJ typed; null = not shown.
     */
    @Column(length = LINK_MAX)
    private String instagramUrl;

    @Column(length = LINK_MAX)
    private String facebookUrl;

    @Column(length = LINK_MAX)
    private String tiktokUrl;

    /** How the AI words its comment to the guest (V22); {@link CommentStyle#CLASSIC} adds nothing to the prompt. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommentStyle commentStyle = CommentStyle.CLASSIC;

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
