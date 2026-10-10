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
        assertThat(converter.convertToDatabaseColumn(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("QUEUE, HISTORY,,DELETE_EVERYTHING"))
                .containsExactly(StaffPermission.QUEUE, StaffPermission.HISTORY);
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(StaffPermission.all()))).isEqualTo(StaffPermission.all());
    }
}
