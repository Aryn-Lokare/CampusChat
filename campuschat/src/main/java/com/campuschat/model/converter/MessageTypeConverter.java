package com.campuschat.model.converter;

import com.campuschat.model.MessageType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MessageTypeConverter implements AttributeConverter<MessageType, String> {

    @Override
    public String convertToDatabaseColumn(MessageType type) {
        return type != null ? type.name() : MessageType.CHAT.name();
    }

    @Override
    public MessageType convertToEntityAttribute(String dbData) {
        if (dbData == null) return MessageType.CHAT;
        return MessageType.fromString(dbData);
    }
}
