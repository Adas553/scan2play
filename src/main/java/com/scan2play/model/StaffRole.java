package com.scan2play.model;

import java.util.EnumSet;
import java.util.Set;

import static com.scan2play.model.StaffPermission.*;

/**
 * A ready set of {@link StaffPermission}s the organiser gives a person of the staff in one pick (V32, the owner 2026-10-10: roles by
 * what the person does, not by their job — a bartender, a second DJ, the venue's manager or a friend). {@link #CUSTOM}: the
 * organiser ticked the permissions one by one. The role is the label; the permissions are what the server checks. Stored by name
 * ({@code party_staff.role}, a check constraint lists them); the order is the order of the organiser's list.
 */
public enum StaffRole {
    /** Sees the queue and the history; changes nothing. */
    VIEWER(EnumSet.of(HISTORY)),
    /** Works the queue: what every person of the staff could do before V32 — the default of a person who joins. */
    QUEUE(EnumSet.of(StaffPermission.QUEUE, TIPS, CLEAR_QUEUE, OPEN_CLOSE, HISTORY)),
    /** Everything the organiser may hand over. */
    CO_ORGANISER(EnumSet.allOf(StaffPermission.class)),
    /** Ticked one by one. */
    CUSTOM(null);

    /** The role of a person who joins. */
    public static final StaffRole DEFAULT = QUEUE;

    private final Set<StaffPermission> permissions;

    StaffRole(Set<StaffPermission> permissions) {
        this.permissions = permissions;
    }

    /** The role's permissions (a copy); {@link #CUSTOM} has none of its own. */
    public Set<StaffPermission> permissions() {
        return permissions == null ? EnumSet.noneOf(StaffPermission.class) : EnumSet.copyOf(permissions);
    }

    /** The role's permissions as stored and as the owner's page compares them ("QUEUE,TIPS,…"; {@link #CUSTOM}: ""). */
    public String permissionNames() {
        return StaffPermission.format(permissions());
    }

    /** The role whose set this is, or {@link #CUSTOM}: the organiser's ticks shown as the role they make. */
    public static StaffRole of(Set<StaffPermission> permissions) {
        for (StaffRole role : values()) {
            if (role.permissions != null && role.permissions.equals(permissions)) {
                return role;
            }
        }
        return CUSTOM;
    }
}
