package com.quorum.placementservice.event;

public final class EventTypes {

    public static final String DRIVE_SCHEDULED = "DRIVE_SCHEDULED";
    public static final String ROUND_CREATED = "ROUND_CREATED";
    public static final String SESSION_SCHEDULED = "SESSION_SCHEDULED";
    public static final String SESSION_UPDATED = "SESSION_UPDATED";
    public static final String SESSION_CANCELLED = "SESSION_CANCELLED";

    public static final int SCHEMA_VERSION = 1;

    private EventTypes() {
    }
}
