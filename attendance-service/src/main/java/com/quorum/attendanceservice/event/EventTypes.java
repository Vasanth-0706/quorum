package com.quorum.attendanceservice.event;

/** The eventTypes of the non-drive topics. drive-events types are in {@link DriveEventTypes}. */
public final class EventTypes {

    public static final int SCHEMA_VERSION = 1;

    /** clash-events, from timetable-service. */
    public static final String CLASHES_COMPUTED = "CLASHES_COMPUTED";

    /** checkin-events, from checkin-service. */
    public static final String ATTENDANCE_MARKED = "ATTENDANCE_MARKED";

    /** import-events, from import-service. Read only by the TEMPORARY file-attendance bridge. */
    public static final String ATTENDANCE_UPLOADED = "ATTENDANCE_UPLOADED";

    /** od-events, produced here. */
    public static final String OD_GRANTED = "OD_GRANTED";
    public static final String OD_REVOKED = "OD_REVOKED";

    private EventTypes() {
    }
}
