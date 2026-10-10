package com.scan2play.entity;

import com.scan2play.model.StaffPermission;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Set;

/**
 * {@code party_staff.permissions} (V32), {@code party_settings.staff_link_permissions} (V33), {@code staff_invitation.permissions}
 * (V34): the names of the {@link StaffPermission}s, comma-separated — readable in SQL too. Null stays null (no link); none is "".
 */
@Converter
public class StaffPermissionsConverter implements AttributeConverter<Set<StaffPermission>, String> {

    @Override
    public String convertToDatabaseColumn(Set<StaffPermission> permissions) {
        return permissions == null ? null : StaffPermission.format(permissions);
    }

    @Override
    public Set<StaffPermission> convertToEntityAttribute(String stored) {
        return stored == null ? null : StaffPermission.parse(stored);
    }
}
