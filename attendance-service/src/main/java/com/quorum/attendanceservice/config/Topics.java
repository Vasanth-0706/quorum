package com.quorum.attendanceservice.config;

/** Every Kafka topic this service touches. Never type a topic name anywhere else. */
public final class Topics {

    public static final String DLT_SUFFIX = ".DLT";

    /** Consumed: rounds and sessions from placement-service. */
    public static final String DRIVE_EVENTS = "drive-events";
    public static final String DRIVE_EVENTS_DLT = DRIVE_EVENTS + DLT_SUFFIX;

    /** Consumed: CLASHES_COMPUTED from timetable-service. */
    public static final String CLASH_EVENTS = "clash-events";
    public static final String CLASH_EVENTS_DLT = CLASH_EVENTS + DLT_SUFFIX;

    /** Consumed: ATTENDANCE_MARKED from checkin-service. */
    public static final String CHECKIN_EVENTS = "checkin-events";
    public static final String CHECKIN_EVENTS_DLT = CHECKIN_EVENTS + DLT_SUFFIX;

    /**
     * Consumed only while the TEMPORARY file-attendance bridge is on
     * ({@code quorum.file-attendance-bridge.enabled}): ATTENDANCE_UPLOADED from import-service.
     */
    public static final String IMPORT_EVENTS = "import-events";
    public static final String IMPORT_EVENTS_DLT = IMPORT_EVENTS + DLT_SUFFIX;

    /** Produced: OD_GRANTED / OD_REVOKED, key = studentId. */
    public static final String OD_EVENTS = "od-events";

    private Topics() {
    }
}
