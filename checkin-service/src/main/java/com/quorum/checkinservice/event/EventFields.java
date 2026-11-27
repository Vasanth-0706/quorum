package com.quorum.checkinservice.event;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Tolerant readers for incoming JSON: unknown fields are ignored, bad required ones are invalid. */
public final class EventFields {

    private static final Pattern STUDENT_ID = Pattern.compile("\\d{10}");

    private EventFields() {
    }

    public static String optionalText(JsonNode e, String field) {
        JsonNode n = e.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asString().trim();
        return s.isEmpty() ? null : s;
    }

    public static String text(JsonNode e, String field) {
        String s = optionalText(e, field);
        if (s == null) {
            throw new InvalidEventException("Missing " + field);
        }
        return s;
    }

    public static String text(JsonNode e, String field, int maxLength) {
        String s = text(e, field);
        if (s.length() > maxLength) {
            throw new InvalidEventException(field + " is longer than " + maxLength);
        }
        return s;
    }

    public static long id(JsonNode e, String field) {
        JsonNode n = e.get(field);
        if (n != null && n.isIntegralNumber()) {
            return n.asLong();
        }
        try {
            return Long.parseLong(text(e, field));
        } catch (NumberFormatException ex) {
            throw new InvalidEventException(field + " is not a number", ex);
        }
    }

    public static int integer(JsonNode e, String field) {
        long v = id(e, field);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new InvalidEventException(field + " is out of range");
        }
        return (int) v;
    }

    public static LocalDate date(JsonNode e, String field) {
        return parse(e, field, LocalDate::parse);
    }

    public static LocalTime time(JsonNode e, String field) {
        return parse(e, field, LocalTime::parse);
    }

    /** An optional time: null when missing or null. */
    public static LocalTime optionalTime(JsonNode e, String field) {
        return optionalText(e, field) == null ? null : time(e, field);
    }

    /** A JSON boolean, or the strings "true" / "false". */
    public static boolean bool(JsonNode e, String field) {
        JsonNode n = e.get(field);
        if (n != null && n.isBoolean()) {
            return n.asBoolean();
        }
        String s = text(e, field);
        return switch (s.toLowerCase()) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new InvalidEventException(field + " is not a boolean: " + s);
        };
    }

    /** ISO-8601 with an offset ({@code 2026-12-07T04:05:00Z}); without one it is read as UTC. */
    public static Instant instant(JsonNode e, String field) {
        String s = text(e, field);
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException withOffset) {
            try {
                return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ex) {
                throw new InvalidEventException(field + " is not valid: " + s, ex);
            }
        }
    }

    public static String studentId(JsonNode e, String field) {
        return studentId(text(e, field));
    }

    public static String studentId(String value) {
        if (value == null || !STUDENT_ID.matcher(value).matches()) {
            throw new InvalidEventException("Student ID must be exactly 10 digits: " + value);
        }
        return value;
    }

    /** The array in {@code field}, or an empty array node when missing or null. */
    public static JsonNode array(JsonNode e, String field) {
        JsonNode n = e.get(field);
        if (n == null || n.isNull()) {
            return JsonNodeFactory.instance.arrayNode();
        }
        if (!n.isArray()) {
            throw new InvalidEventException(field + " is not an array");
        }
        return n;
    }

    /** Optional text, cut to at most maxLength characters. */
    public static String optionalText(JsonNode e, String field, int maxLength) {
        String s = optionalText(e, field);
        return s == null || s.length() <= maxLength ? s : s.substring(0, maxLength);
    }

    private static <T> T parse(JsonNode e, String field, Function<String, T> parser) {
        String s = text(e, field);
        try {
            return parser.apply(s);
        } catch (DateTimeParseException ex) {
            throw new InvalidEventException(field + " is not valid: " + s, ex);
        }
    }
}
