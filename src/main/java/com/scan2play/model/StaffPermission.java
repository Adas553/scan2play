package com.scan2play.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What one person of a party's staff may do (V32, the owner 2026-10-10): the organiser picks it per person — a role
 * ({@link StaffRole}) or each of these. Every person of the staff sees the queue, the QR code and its print and gets the
 * notifications; what is never delegated stays the organiser's alone: the staff, the DJ's profiles and tip link, clearing the
 * history, the account. The server checks each of them ({@code DjSessionHelper.require}); a hidden button is only the look.
 * <p>
 * Stored by name, comma-separated, in {@code party_staff.permissions} ({@code StaffPermissionsConverter}); the order is the order of
 * the organiser's checkboxes.
 */
public enum StaffPermission {
    /** "Zagrane", "Pomiń", "Cofnij", "↩ Przywróć". */
    QUEUE,
    /** 💸 at a song in the queue. */
    TIPS,
    /** "Wyczyść kolejkę". */
    CLEAR_QUEUE,
    /** "Zakończ imprezę" / "Wznów imprezę". */
    OPEN_CLOSE,
    /** The History tab and the standalone history page. */
    HISTORY,
    /** The card "Impreza": the vibe, the AI's comments, the vibe note, "Kto gra". */
    VIBE,
    /** "Limity gości". */
    LIMITS,
    /** The hosts' lists and their link. */
    HOST_LISTS,
    /** "Podsumowanie wieczoru" and its CSV. */
    SUMMARY;

    /** Everything that can be delegated. */
    public static Set<StaffPermission> all() {
        return EnumSet.allOf(StaffPermission.class);
    }

    /** As stored: the names, comma-separated, in the enum's order; none is "". */
    public static String format(Set<StaffPermission> permissions) {
        return permissions.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    /** The stored names back; a name this version does not know (a downgrade) is left out. */
    public static Set<StaffPermission> parse(String stored) {
        EnumSet<StaffPermission> permissions = EnumSet.noneOf(StaffPermission.class);
        if (stored == null) {
            return permissions;
        }
        Arrays.stream(stored.split(",")).map(String::strip).filter(name -> !name.isEmpty()).forEach(name -> {
            try {
                permissions.add(valueOf(name));
            } catch (IllegalArgumentException e) {
                // unknown: not granted
            }
        });
        return permissions;
    }
}
