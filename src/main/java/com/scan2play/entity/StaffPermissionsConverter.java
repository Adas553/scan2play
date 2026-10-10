package com.scan2play.entity;

import com.scan2play.model.StaffPermission;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.EnumSet;
import java.util.Set;

/** {@code party_staff.permissions} (V32): the names of the {@link StaffPermission}s, comma-separated — readable in SQL too. */
@Converter
public class StaffPermissionsConverter implements AttributeConverter<Set<StaffPermission>, String> {

    @Override
    public String convertToDatabaseColumn(Set<StaffPermission> permissions) {
        return StaffPermission.format(permissions == null ? EnumSet.noneOf(StaffPermission.class) : permissions);
    }

    @Override
    public Set<StaffPermission> convertToEntityAttribute(String stored) {
        return StaffPermission.parse(stored);
    }
}
