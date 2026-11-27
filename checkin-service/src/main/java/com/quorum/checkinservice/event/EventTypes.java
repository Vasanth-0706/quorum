package com.quorum.checkinservice.event;

import java.util.Set;

public final class EventTypes {

    public static final int SCHEMA_VERSION = 1;

    /** drive-events, from placement-service. */
    public static final String SESSION_SCHEDULED = "SESSION_SCHEDULED";
    public static final String SESSION_UPDATED = "SESSION_UPDATED";
    public static final String SESSION_CANCELLED = "SESSION_CANCELLED";
    public static final Set<String> SESSION_TYPES = Set.of(SESSION_SCHEDULED, SESSION_UPDATED, SESSION_CANCELLED);

    /** import-events, from import-service. */
    public static final String ATTENDANCE_UPLOADED = "ATTENDANCE_UPLOADED";

    /** checkin-events, produced here. */
    public static final String ATTENDANCE_MARKED = "ATTENDANCE_MARKED";

    private EventTypes() {
    }
}
