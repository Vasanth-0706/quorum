package com.quorum.checkinservice.config;

/** Every Kafka topic this service touches. Never type a topic name anywhere else. */
public final class Topics {

    public static final String DLT_SUFFIX = ".DLT";

    /** Consumed: sessions (room, date, time, status) from placement-service. */
    public static final String DRIVE_EVENTS = "drive-events";
    public static final String DRIVE_EVENTS_DLT = DRIVE_EVENTS + DLT_SUFFIX;

    /** Consumed: ATTENDANCE_UPLOADED from import-service (other types are skipped). */
    public static final String IMPORT_EVENTS = "import-events";
    public static final String IMPORT_EVENTS_DLT = IMPORT_EVENTS + DLT_SUFFIX;

    /** Produced: ATTENDANCE_MARKED, key = sessionId. */
    public static final String CHECKIN_EVENTS = "checkin-events";

    private Topics() {
    }
}
