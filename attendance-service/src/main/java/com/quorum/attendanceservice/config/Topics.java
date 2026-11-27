package com.quorum.attendanceservice.config;

/** Every Kafka topic this service touches. Never type a topic name anywhere else. */
public final class Topics {

    public static final String DLT_SUFFIX = ".DLT";

    public static final String DRIVE_EVENTS = "drive-events";
    public static final String DRIVE_EVENTS_DLT = DRIVE_EVENTS + DLT_SUFFIX;

    private Topics() {
    }
}
