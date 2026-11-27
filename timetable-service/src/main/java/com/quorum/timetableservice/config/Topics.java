package com.quorum.timetableservice.config;

/** Every Kafka topic this service touches. Never type a topic name anywhere else. */
public final class Topics {

    public static final String DLT_SUFFIX = ".DLT";

    /** Consumed: timetables and shortlists from import-service. */
    public static final String IMPORT_EVENTS = "import-events";
    public static final String IMPORT_EVENTS_DLT = IMPORT_EVENTS + DLT_SUFFIX;

    /** Consumed: rounds and sessions from placement-service. */
    public static final String DRIVE_EVENTS = "drive-events";
    public static final String DRIVE_EVENTS_DLT = DRIVE_EVENTS + DLT_SUFFIX;

    /** Produced: CLASHES_COMPUTED, key = roundId. */
    public static final String CLASH_EVENTS = "clash-events";

    private Topics() {
    }
}
