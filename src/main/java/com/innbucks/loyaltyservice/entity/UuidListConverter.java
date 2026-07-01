package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stores a {@code List<UUID>} as a comma-separated VARCHAR so columns like
 * {@code voucher_templates.applicable_outlets} can keep their existing
 * VARCHAR(1000) schema while the Java side speaks in typed lists.
 *
 * <p>Empty/null lists round-trip as {@code null} so callers can distinguish
 * "no restriction" from "explicitly empty".
 */
@Converter
public class UuidListConverter implements AttributeConverter<List<UUID>, String> {

    @Override
    public String convertToDatabaseColumn(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return null;
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    @Override
    public List<UUID> convertToEntityAttribute(String joined) {
        if (joined == null || joined.isBlank()) return null;
        List<UUID> out = new ArrayList<>();
        for (String part : Arrays.asList(joined.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(UUID.fromString(trimmed));
        }
        return out.isEmpty() ? null : out;
    }
}
