/* Copyright 2026 eBlocker contributors. Licensed under EUPL-1.2. */
package org.eblocker.server.http.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.eblocker.server.common.data.InternetAccessContingent;
import org.eblocker.server.common.data.UserModule;
import org.eblocker.server.common.data.UserProfileModule;
import org.eblocker.server.common.data.UserRole;
import org.restexpress.exception.BadRequestException;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Strict field patches: credentials, ownership and usage history are never client writable. */
public final class FamilySettingsPatch {
    private static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> USER = Set.of("name", "nameKey", "birthday", "userRole", "associatedProfileId");
    private static final Set<String> PROFILE = Set.of("name", "description", "controlmodeUrls", "controlmodeTime",
            "controlmodeMaxUsage", "internetAccessRestrictionMode", "accessibleSitesPackages", "inaccessibleSitesPackages",
            "internetAccessContingents", "maxUsageTimeByDay", "parentalControlSettingValidated");
    private final JsonNode values;
    private FamilySettingsPatch(JsonNode values) { this.values = values; }

    public static FamilySettingsPatch user(String body) {
        JsonNode fields = parse(body, USER);
        fields.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            switch (entry.getKey()) {
                case "name": text(value, 16, false); break;
                case "nameKey": if (!value.isNull()) text(value, 256, false); break;
                case "birthday": birthday(value); break;
                case "userRole":
                    if (!value.isTextual() || !Set.of("PARENT", "CHILD", "OTHER").contains(value.textValue())) invalid();
                    break;
                case "associatedProfileId": integer(value, 0, Integer.MAX_VALUE); break;
                default: invalid();
            }
        });
        return new FamilySettingsPatch(fields);
    }

    public static FamilySettingsPatch profile(String body) {
        JsonNode fields = parse(body, PROFILE);
        fields.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            switch (entry.getKey()) {
                case "name": text(value, 128, false); break;
                case "description": text(value, 4096, true); break;
                case "internetAccessRestrictionMode": integer(value, 0, 2); break;
                case "accessibleSitesPackages": case "inaccessibleSitesPackages": ids(value); break;
                case "internetAccessContingents": contingents(value); break;
                case "maxUsageTimeByDay":
                    if (!value.isObject() || value.isEmpty() || value.size() > 7) invalid();
                    value.fields().forEachRemaining(day -> {
                        try { DayOfWeek.valueOf(day.getKey()); } catch (IllegalArgumentException e) { invalid(); }
                        integer(day.getValue(), 0, 1440);
                    });
                    break;
                default: if (!value.isBoolean()) invalid();
            }
        });
        return new FamilySettingsPatch(fields);
    }

    public static Integer assignment(String body) {
        JsonNode value = parse(body, Set.of("userId")).get("userId");
        return value.isNull() ? null : integer(value, 0, Integer.MAX_VALUE);
    }

    public static int id(String value) {
        try {
            int id = Integer.parseInt(value);
            if (id < 0) invalid();
            return id;
        } catch (NumberFormatException e) { throw new BadRequestException("error.family.invalidId"); }
    }

    private static JsonNode parse(String body, Set<String> allowed) {
        try {
            if (body == null || body.length() > 65536) invalid();
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject() || node.isEmpty()) invalid();
            node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) invalid(); });
            return node;
        } catch (JsonProcessingException e) { throw new BadRequestException("error.family.invalidJson"); }
    }

    private static void invalid() { throw new BadRequestException("error.family.invalidSettings"); }
    private static int integer(JsonNode node, int minimum, int maximum) {
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < minimum || node.intValue() > maximum) invalid();
        return node.intValue();
    }
    private static String text(JsonNode node, int maximum, boolean emptyAllowed) {
        if (!node.isTextual() || node.textValue().length() > maximum || (!emptyAllowed && node.textValue().strip().isEmpty())
                || node.textValue().codePoints().anyMatch(c -> Character.isISOControl(c)
                && (!emptyAllowed || c != '\n' && c != '\t'))) invalid();
        return node.textValue().strip();
    }
    private static LocalDate birthday(JsonNode node) {
        if (node.isNull()) return null;
        if (!node.isArray() || node.size() != 3) invalid();
        try { return LocalDate.of(integer(node.get(0), 1, 9999), integer(node.get(1), 1, 12), integer(node.get(2), 1, 31)); }
        catch (DateTimeException e) { throw new BadRequestException("error.family.invalidBirthday"); }
    }
    private static Set<Integer> ids(JsonNode node) {
        if (!node.isArray() || node.size() > 4096) invalid();
        Set<Integer> result = new LinkedHashSet<>();
        node.forEach(id -> { if (!result.add(integer(id, 0, Integer.MAX_VALUE))) invalid(); });
        return result;
    }
    private static Set<InternetAccessContingent> contingents(JsonNode node) {
        if (!node.isArray() || node.size() > 256) invalid();
        Set<InternetAccessContingent> result = new LinkedHashSet<>();
        node.forEach(window -> {
            if (!window.isObject() || !window.has("onDay") || !window.has("fromMinutes") || !window.has("tillMinutes")) invalid();
            window.fieldNames().forEachRemaining(field -> {
                if (!Set.of("onDay", "fromMinutes", "tillMinutes", "totalMinutes").contains(field)) invalid();
            });
            int day = integer(window.get("onDay"), 1, 9);
            int from = integer(window.get("fromMinutes"), 0, 1439);
            int till = integer(window.get("tillMinutes"), 1, 1440);
            Integer total = !window.has("totalMinutes") || window.get("totalMinutes").isNull()
                    ? null : integer(window.get("totalMinutes"), 0, 1440);
            if (from >= till || !result.add(new InternetAccessContingent(day, from, till, total))) invalid();
        });
        return result;
    }

    public Set<Integer> changedFilterIds() {
        Set<Integer> result = new HashSet<>();
        for (String field : Set.of("accessibleSitesPackages", "inaccessibleSitesPackages")) {
            if (values.has(field)) result.addAll(ids(values.get(field)));
        }
        return result;
    }

    public UserModule applyUser(UserModule current) {
        UserModule next = JSON.convertValue(JSON.valueToTree(current), UserModule.class);
        if (values.has("name")) { next.setName(values.get("name").textValue().strip()); next.setNameKey(null); }
        if (values.has("nameKey")) next.setNameKey(values.get("nameKey").isNull() ? null : values.get("nameKey").textValue());
        if (values.has("birthday")) next.setBirthday(birthday(values.get("birthday")));
        if (values.has("userRole")) next.setUserRole(UserRole.valueOf(values.get("userRole").textValue()));
        if (values.has("associatedProfileId")) next.setAssociatedProfileId(values.get("associatedProfileId").intValue());
        return next;
    }

    public void applyProfile(UserProfileModule next) {
        if (values.has("name")) { next.setName(values.get("name").textValue().strip()); next.setNameKey(null); }
        if (values.has("description")) { next.setDescription(values.get("description").textValue()); next.setDescriptionKey(null); }
        if (values.has("controlmodeUrls")) next.setControlmodeUrls(values.get("controlmodeUrls").booleanValue());
        if (values.has("controlmodeTime")) next.setControlmodeTime(values.get("controlmodeTime").booleanValue());
        if (values.has("controlmodeMaxUsage")) next.setControlmodeMaxUsage(values.get("controlmodeMaxUsage").booleanValue());
        if (values.has("parentalControlSettingValidated")) next.setParentalControlSettingValidated(values.get("parentalControlSettingValidated").booleanValue());
        if (values.has("internetAccessRestrictionMode")) next.setInternetAccessRestrictionMode(UserProfileModule.InternetAccessRestrictionMode.values()[values.get("internetAccessRestrictionMode").intValue()]);
        if (values.has("accessibleSitesPackages")) next.setAccessibleSitesPackages(ids(values.get("accessibleSitesPackages")));
        if (values.has("inaccessibleSitesPackages")) next.setInaccessibleSitesPackages(ids(values.get("inaccessibleSitesPackages")));
        if (values.has("internetAccessContingents")) next.setInternetAccessContingents(contingents(values.get("internetAccessContingents")));
        if (values.has("maxUsageTimeByDay")) {
            var limits = new HashMap<DayOfWeek, Integer>(next.getMaxUsageTimeByDay());
            values.get("maxUsageTimeByDay").fields().forEachRemaining(day -> limits.put(DayOfWeek.valueOf(day.getKey()), day.getValue().intValue()));
            next.setMaxUsageTimeByDay(limits);
        }
    }
}
