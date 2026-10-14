package com.campuschat.model.converter;

import com.campuschat.model.UserStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class UserStatusConverter implements AttributeConverter<UserStatus, String> {

    @Override
    public String convertToDatabaseColumn(UserStatus status) {
        return status != null ? status.name() : UserStatus.ACTIVE.name();
    }

    @Override
    public UserStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) return UserStatus.ACTIVE;
        return UserStatus.fromString(dbData);
    }
}
