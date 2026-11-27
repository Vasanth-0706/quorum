package com.quorum.timetableservice.event;

import java.util.Set;

public final class EventTypes {

    // import-events
    public static final String TIMETABLE_UPLOADED = "TIMETABLE_UPLOADED";
    public static final String SHORTLIST_UPLOADED = "SHORTLIST_UPLOADED";

    // drive-events
    public static final String ROUND_CREATED = "ROUND_CREATED";
    public static final String SESSION_SCHEDULED = "SESSION_SCHEDULED";
    public static final String SESSION_UPDATED = "SESSION_UPDATED";
    public static final String SESSION_CANCELLED = "SESSION_CANCELLED";

    // clash-events (produced)
    public static final String CLASHES_COMPUTED = "CLASHES_COMPUTED";

    public static final int SCHEMA_VERSION = 1;

    public static final Set<String> IMPORT_HANDLED = Set.of(TIMETABLE_UPLOADED, SHORTLIST_UPLOADED);
    public static final Set<String> SESSION_TYPES = Set.of(SESSION_SCHEDULED, SESSION_UPDATED, SESSION_CANCELLED);
    public static final Set<String> DRIVE_HANDLED = Set.of(ROUND_CREATED, SESSION_SCHEDULED, SESSION_UPDATED,
            SESSION_CANCELLED);

    private EventTypes() {
    }
}
