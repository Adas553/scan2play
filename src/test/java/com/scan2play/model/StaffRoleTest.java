package com.scan2play.model;

import com.scan2play.entity.StaffPermissionsConverter;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/** The staff's roles and permissions (V32): the sets of the roles, how a set is named, how it is stored. */
class StaffRoleTest {

    @Test
    void theRoles_growFromLookingToEverythingThatCanBeHandedOver() {
        assertThat(StaffRole.VIEWER.permissions()).containsExactly(StaffPermission.HISTORY);
        assertThat(StaffRole.QUEUE.permissions()).as("what every person of the staff could do before V32 — V32 gives it to them")
                .containsExactly(StaffPermission.QUEUE, StaffPermission.TIPS, StaffPermission.CLEAR_QUEUE, StaffPermission.OPEN_CLOSE,
                        StaffPermission.HISTORY);
        assertThat(StaffRole.CO_ORGANISER.permissions()).isEqualTo(StaffPermission.all());
        assertThat(StaffRole.CUSTOM.permissions()).isEmpty();
        assertThat(StaffRole.DEFAULT).isEqualTo(StaffRole.QUEUE);
    }

    @Test
    void aSet_isNamedByTheRoleThatMakesIt_elseCustom() {
        for (StaffRole role : new StaffRole[]{StaffRole.VIEWER, StaffRole.QUEUE, StaffRole.CO_ORGANISER}) {
            assertThat(StaffRole.of(role.permissions())).isEqualTo(role);
        }
        assertThat(StaffRole.of(EnumSet.of(StaffPermission.QUEUE))).isEqualTo(StaffRole.CUSTOM);
        assertThat(StaffRole.of(EnumSet.noneOf(StaffPermission.class))).isEqualTo(StaffRole.CUSTOM);
    }

    @Test
    void thePermissions_areStoredByName_inTheirOrder_andAnUnknownNameIsNotGranted() {
        StaffPermissionsConverter converter = new StaffPermissionsConverter();

        assertThat(converter.convertToDatabaseColumn(EnumSet.of(StaffPermission.HISTORY, StaffPermission.QUEUE))).isEqualTo("QUEUE,HISTORY");
        assertThat(converter.convertToDatabaseColumn(EnumSet.noneOf(StaffPermission.class))).isEmpty();
        assertThat(converter.convertToEntityAttribute("QUEUE, HISTORY,,DELETE_EVERYTHING"))
                .containsExactly(StaffPermission.QUEUE, StaffPermission.HISTORY);
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
        // null stays null (V33: no invitation link, so no role for one) — never "none granted" in disguise
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(StaffPermission.all()))).isEqualTo(StaffPermission.all());
    }

    /**
     * V56 gave KIOSK to the sets that were the whole set before it, matched as stored (the names in the enum's order): the migration's
     * words must be what {@link StaffPermission#format} wrote for that set, or no co-organiser would get the kiosk. The next permission
     * added the same way gets the same check.
     */
    @Test
    void theKiosksMigration_matchesTheWholeSetAsItWasStored() throws java.io.IOException {
        EnumSet<StaffPermission> before = EnumSet.allOf(StaffPermission.class);
        before.remove(StaffPermission.KIOSK);
        String sql;
        try (java.io.InputStream in = getClass().getResourceAsStream("/db/migration/V56__kiosk.sql")) {
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(sql.split("= '" + StaffPermission.format(before) + "';", -1)).as("the three tables").hasSize(4);
        before.add(StaffPermission.KIOSK);
        assertThat(StaffRole.of(StaffPermission.parse(StaffPermission.format(before)))).isEqualTo(StaffRole.CO_ORGANISER);
    }
}
