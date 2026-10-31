package com.scan2play.entity;

import com.scan2play.model.CommentStyle;
import com.scan2play.model.PosterFont;
import com.scan2play.model.PosterSize;
import com.scan2play.model.PosterLanguage;
import com.scan2play.model.PosterTheme;
import com.scan2play.model.StaffPermission;
import com.scan2play.model.StaffRole;
import com.scan2play.model.VibeType;
import com.scan2play.util.CodeGenerator;
import com.scan2play.util.SongList;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

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

    /**
     * How long the hosts' link and the staff's invitation link work from when they were made (V37): as an invitation by address
     * ({@code StaffInvitationEntity.MAX_AGE_DAYS}) — a link given for one event does not stay open for good.
     */
    public static final int LINK_DAYS = 30;

    /** The longest organiser's name kept (the column, V32). */
    public static final int OWNER_NAME_MAX = 100;
    /** The longest organiser's address kept (the column, V48): an e-mail address is at most 320 characters. */
    public static final int OWNER_EMAIL_MAX = 320;

    /** The longest vibe note (the column, V16). */
    public static final int VIBE_NOTE_MAX = 150;
    /** The longest DJ name (the column, V17). */
    public static final int DJ_NAME_MAX = 60;
    /** The longest address of a DJ's profile (V24). */
    public static final int LINK_MAX = 200;

    /** A new party's guests' limits — and every party's without the Pro plan (V35, {@code PlanService.asPlanned}). */
    public static final int DEFAULT_REQUEST_LIMIT = 2;
    public static final int DEFAULT_COOLDOWN_MINUTES = 3;
    public static final int DEFAULT_DUPLICATE_CHECK_WINDOW = 15;

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
     * Whether the guests may add a dedication to a request (V38): the field under the song's on the party page, and the server takes
     * one only while this is on. On by default; the DJ switches it in the card "Impreza".
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean dedicationsOn = true;

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

    /**
     * The DJ's tip link (V27): the DJ's page on a service that takes tips, as {@code util.TipLinks} wrote it — the guests pay
     * there, straight to the DJ; null = no "💸 Napiwek dla DJ-a" button.
     */
    @Column(length = LINK_MAX)
    private String tipUrl;

    /**
     * The hosts' lists (V29), one song or artist per line as {@code util.SongList} keeps them; null = empty. A request for a song
     * of {@code hostBlocked} ("🚫 nie grać") is refused before it reaches the DJ; one of {@code hostWanted} ("⭐ koniecznie") is
     * accepted even against the AI's verdict and marked in the queue.
     */
    @Column(length = SongList.TEXT_MAX)
    private String hostBlocked;

    @Column(length = SongList.TEXT_MAX)
    private String hostWanted;

    /** The secret of the hosts' link ({@code /h/{token}}, V29): they fill the lists without an account; null = no link. */
    @Column(length = 32, unique = true)
    @ToString.Exclude
    private String hostToken;

    /** When the DJ made the hosts' link (V37): it works {@value #LINK_DAYS} days ({@link #hostLinkWorks}). */
    @ToString.Exclude
    private Instant hostTokenMadeAt;

    /**
     * The secret of the venue's kiosk link ({@code /k/{token}}, V56): a tablet where guests without a phone ask and vote; null = no
     * link. It does not run out — a tablet stays in the venue —; a new one makes the old link dead.
     */
    @Column(length = 32, unique = true)
    @ToString.Exclude
    private String kioskToken;

    /** The secret of the staff's invitation link ({@code /join/{token}}, V30): a bartender joins with it; null = no link. */
    @Column(length = 32, unique = true)
    @ToString.Exclude
    private String staffToken;

    /** When the organiser made the invitation link (V37): it works {@value #LINK_DAYS} days ({@link #staffLinkWorks}). */
    @ToString.Exclude
    private Instant staffTokenMadeAt;

    /**
     * What whoever joins by the invitation link may do (V33): the organiser's pick when making the link — a role's set or ticked one
     * by one; null while there is no link. A new role is a new link ({@code StaffController.updateStaffLink}).
     */
    @Convert(converter = StaffPermissionsConverter.class)
    @Column(length = 200)
    private Set<StaffPermission> staffLinkPermissions;

    /**
     * The organiser's name from their Google account (V32), kept when they open the panel: the party's staff see it where the party has
     * no "Kto gra" ("Klub Ola" or "Ola Kowalska", not the code). Null until the organiser opens the panel after V32.
     */
    @Column(length = OWNER_NAME_MAX)
    private String ownerName;

    /**
     * The organiser's verified address from their Google account (V48), kept as {@link #ownerName}: only to write to them about the
     * account and help (the privacy policy says so). Null until the organiser opens the panel after V48.
     */
    @Column(length = OWNER_EMAIL_MAX)
    @ToString.Exclude   // a person's address: never in a log line
    private String ownerEmail;

    /** When the party was made (V48, the database's own {@code now()}); null for the parties of before, whose day nobody kept. */
    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    /** When the organiser closed the panel's welcome card (V48); null = the card is shown. The parties of before count as closed. */
    private Instant welcomeDismissedAt;

    /**
     * When the party opens by itself (V55), null = not planned; {@link #nextCloseAt} when it closes. Written only through
     * {@code PartySettingsCommandService} — the DJ's "Godziny imprezy", a manual open or close ({@code PartySchedule#settle}) and the
     * job that applies a moment that has come; never one that would change nothing.
     */
    private Instant nextOpenAt;

    /** When the party closes by itself (V55), null = not planned. */
    private Instant nextCloseAt;

    /** How the AI words its comment to the guest (V22); {@link CommentStyle#CLASSIC} adds nothing to the prompt. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommentStyle commentStyle = CommentStyle.CLASSIC;

    // --- Rate Limiting ---
    @Builder.Default
    @Column(nullable = false)
    private int requestLimit = DEFAULT_REQUEST_LIMIT;

    @Builder.Default
    @Column(nullable = false)
    private int cooldownMinutes = DEFAULT_COOLDOWN_MINUTES;

    // --- Duplicate Filtering ---
    @Builder.Default
    @Column(nullable = false)
    private int duplicateCheckWindow = DEFAULT_DUPLICATE_CHECK_WINDOW;

    /**
     * The version of the DJ's logo (V45, {@code PartyLogoService}), null = none: written only by that service's SQL with the logo's
     * row, so a save of the settings never overwrites it.
     */
    @Column(insertable = false, updatable = false)
    private String logoVersion;

    /** The longest heading and line of the DJ's own on the QR print (the columns, V46): kept to one A4 poster and a card. */
    public static final int POSTER_HEADING_MAX = 40;
    public static final int POSTER_LINE_MAX = 60;

    /** The QR print as the DJ sets it (V46): the DJ's own heading in place of ours, null = ours. */
    @Column(length = POSTER_HEADING_MAX)
    private String posterHeading;

    /** One line of the DJ's own text under the heading ("Osiemnastka Dawida"), null = none. */
    @Column(length = POSTER_LINE_MAX)
    private String posterLine;

    /** The print's language; {@link PosterLanguage#AUTO} = the panel's. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private PosterLanguage posterLanguage = PosterLanguage.AUTO;

    /** The print's look (V47): its theme, and the DJ's colour for the heading and the frame ("#rrggbb"; null = the theme's). */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PosterTheme posterTheme = PosterTheme.CLASSIC;

    @Column(length = 7)
    private String posterAccent;

    /** The paper's colour (V53), "#rrggbb", made a light tint by the page; null = the theme's paper. */
    @Column(length = 7)
    private String posterPaper;

    /** The heading's font (V51); {@link PosterFont#THEME} = the theme's own. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private PosterFont posterFont = PosterFont.THEME;

    /** The print's heading size (V54): smaller, as the theme has it, or larger. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private PosterSize posterSize = PosterSize.NORMAL;

    /** The most tables the print numbers (V50): 50 tents are 50 sheets. */
    public static final int POSTER_TABLES_MAX = 50;

    /** The number of tables (V50): the cards and the tents numbered 1 … N, as many as there are tables; null = no numbers. */
    private Integer posterTables;

    /** What the print shows of the DJ's: the logo, the profiles, the tip link, and the address with the party's code. */
    @Builder.Default
    @Column(nullable = false)
    private boolean posterShowLogo = true;
    @Builder.Default
    @Column(nullable = false)
    private boolean posterShowLinks = true;
    @Builder.Default
    @Column(nullable = false)
    private boolean posterShowTip = true;
    @Builder.Default
    @Column(nullable = false)
    private boolean posterShowAddress = true;

    /** The address of the DJ's logo, its version in it (a new logo is a new address), or null without one. */
    public String logoPath() {
        return logoVersion == null ? null : "/p/" + partyCode + "/logo.png?v=" + logoVersion.substring(0, Math.min(16, logoVersion.length()));
    }

    /** The hosts' link opens their lists: there is one, and it was made less than {@value #LINK_DAYS} days ago. */
    public boolean hostLinkWorks(Instant now) {
        return hostToken != null && linkWorks(hostTokenMadeAt, now);
    }

    /** The invitation link lets someone join: there is one, and it was made less than {@value #LINK_DAYS} days ago. */
    public boolean staffLinkWorks(Instant now) {
        return staffToken != null && linkWorks(staffTokenMadeAt, now);
    }

    /** The kiosk's address to copy and open on the tablet ({@code cleanBaseUrl/k/{token}}, V56), or null: no kiosk link. */
    public String kioskLink(String cleanBaseUrl) {
        return kioskToken == null ? null : cleanBaseUrl + "/k/" + kioskToken;
    }

    /** Until when a link made at {@code madeAt} works. */
    public static Instant linkWorksUntil(Instant madeAt) {
        return madeAt.plus(Duration.ofDays(LINK_DAYS));
    }

    private static boolean linkWorks(Instant madeAt, Instant now) {
        return madeAt != null && now.isBefore(linkWorksUntil(madeAt));
    }

    /** What the invitation link gives (a copy): its permissions, or the role "Obsługa kolejki" for a link of a version before V33. */
    public Set<StaffPermission> staffLinkGrants() {
        return staffLinkPermissions == null ? StaffRole.DEFAULT.permissions()
                : staffLinkPermissions.isEmpty() ? EnumSet.noneOf(StaffPermission.class) : EnumSet.copyOf(staffLinkPermissions);
    }

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
