/*
 * Copyright 2026 eBlocker Open Source UG (haftungsbeschraenkt)
 * Licensed under the EUPL, Version 1.2.
 */
package org.eblocker.server.http.controller;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.eblocker.server.common.data.Device;
import org.eblocker.server.common.data.IpAddressModule;
import org.restexpress.exception.BadRequestException;

import java.util.Set;

/** A strict, deliberately small alternative to replacing a complete Device. */
public final class DeviceSettingsPatch {
    private static final Set<String> FIELDS = Set.of("name", "enabled", "filterAdsEnabled",
            "filterTrackersEnabled", "malwareFilterEnabled", "sslEnabled");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    // Copy stored fields, including fields without public setters. Derived JSON getters are not state.
    private static final ObjectMapper COPIER = JsonMapper.builder()
            .disable(MapperFeature.USE_ANNOTATIONS)
            .visibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
            .visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .addModule(new JavaTimeModule())
            .addModule(new IpAddressModule())
            .build();

    private final JsonNode values;

    private DeviceSettingsPatch(JsonNode values) {
        this.values = values;
    }

    public static DeviceSettingsPatch parse(String body) {
        final JsonNode values;
        try {
            values = JSON.readTree(body);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("error.device.settings.invalidJson");
        }
        if (values == null || !values.isObject() || values.isEmpty()) {
            throw new BadRequestException("error.device.settings.invalidBody");
        }
        values.fields().forEachRemaining(field -> {
            if (!FIELDS.contains(field.getKey())) {
                throw new BadRequestException("error.device.settings.unknownField");
            }
            JsonNode value = field.getValue();
            if ("name".equals(field.getKey())) {
                if (!value.isTextual()) {
                    throw new BadRequestException("error.device.settings.invalidName");
                }
                String name = value.textValue().strip();
                if (name.isEmpty() || name.length() > 50 || name.codePoints().anyMatch(Character::isISOControl)) {
                    throw new BadRequestException("error.device.settings.invalidName");
                }
            } else if (!value.isBoolean()) {
                throw new BadRequestException("error.device.settings.invalidBoolean");
            }
        });
        return new DeviceSettingsPatch(values);
    }

    public boolean changesEnabled(Device device) {
        return values.has("enabled") && values.get("enabled").booleanValue() != device.isEnabled();
    }

    public Device applyTo(Device current) {
        Device updated = copyOf(current);
        if (values.has("name")) updated.setName(values.get("name").textValue().strip());
        if (values.has("enabled")) updated.setEnabled(values.get("enabled").booleanValue());
        if (values.has("filterAdsEnabled")) updated.setFilterAdsEnabled(values.get("filterAdsEnabled").booleanValue());
        if (values.has("filterTrackersEnabled")) updated.setFilterTrackersEnabled(values.get("filterTrackersEnabled").booleanValue());
        if (values.has("malwareFilterEnabled")) updated.setMalwareFilterEnabled(values.get("malwareFilterEnabled").booleanValue());
        if (values.has("sslEnabled")) updated.setSslEnabled(values.get("sslEnabled").booleanValue());
        return updated;
    }

    public static Device copyOf(Device device) {
        return COPIER.convertValue(device, Device.class);
    }

    public static Device responseCopy(Device device, boolean currentDevice) {
        ObjectNode fields = COPIER.valueToTree(device);
        fields.put("isCurrentDevice", currentDevice);
        return COPIER.convertValue(fields, Device.class);
    }
}
