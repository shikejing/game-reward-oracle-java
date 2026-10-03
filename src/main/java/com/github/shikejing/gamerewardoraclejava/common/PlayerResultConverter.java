package com.github.shikejing.gamerewardoraclejava.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * 参与者成绩列表 <-> JSON 字符串转换器，用于结果快照的持久化。
 */
@Converter
public class PlayerResultConverter implements AttributeConverter<List<PlayerResult>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<PlayerResult>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<PlayerResult> attribute) {
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法序列化对局结果", e);
        }
    }

    @Override
    public List<PlayerResult> convertToEntityAttribute(String dbData) {
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法反序列化对局结果", e);
        }
    }
}
