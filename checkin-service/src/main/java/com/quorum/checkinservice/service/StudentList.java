package com.quorum.checkinservice.service;

import com.quorum.checkinservice.entity.RosterEntry;
import com.quorum.checkinservice.event.EventFields;
import com.quorum.checkinservice.event.InvalidEventException;
import tools.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The students of a SHORTLIST_UPLOADED or ATTENDANCE_UPLOADED: {@code studentIds[]} decides who is in
 * it (as before), and the optional, additive {@code students[]: {studentId, name}} adds names.
 * Events from before names existed have no {@code students} and give null names.
 */
public final class StudentList {

    private StudentList() {
    }

    /** studentId → name (null when unknown), in the order of studentIds[], without repeats. */
    public static Map<String, String> read(JsonNode event) {
        Map<String, String> names = names(event);
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode n : EventFields.array(event, "studentIds")) {
            if (!n.isString() && !n.isIntegralNumber()) {
                throw new InvalidEventException("studentIds[] must hold strings");
            }
            String id = EventFields.studentId(n.asString().trim());
            out.putIfAbsent(id, names.get(id));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> names(JsonNode event) {
        Map<String, String> names = new HashMap<>();
        for (JsonNode s : EventFields.array(event, "students")) {
            if (!s.isObject()) {
                continue; // names are optional: a malformed entry only loses its name
            }
            String id = EventFields.optionalText(s, "studentId");
            String name = clean(EventFields.optionalText(s, "name"));
            if (id != null && name != null) {
                names.putIfAbsent(id, name);
            }
        }
        return names;
    }

    /** Trimmed, inner whitespace collapsed, at most 120 characters; null when blank. */
    static String clean(String name) {
        if (name == null) {
            return null;
        }
        String s = name.strip().replaceAll("\\s+", " ");
        if (s.isEmpty()) {
            return null;
        }
        return s.length() <= RosterEntry.NAME_MAX_LENGTH ? s : s.substring(0, RosterEntry.NAME_MAX_LENGTH);
    }
}
