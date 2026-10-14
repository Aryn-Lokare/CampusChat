package com.campuschat.model.converter;

import com.campuschat.model.Role;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RoleConverter implements AttributeConverter<Role, String> {

    @Override
    public String convertToDatabaseColumn(Role role) {
        return role != null ? role.name() : Role.STUDENT.name();
    }

    @Override
    public Role convertToEntityAttribute(String dbData) {
        if (dbData == null) return Role.STUDENT;
        return Role.fromString(dbData);
    }
}
