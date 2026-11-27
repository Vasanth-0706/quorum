package com.quorum.timetableservice.service;

import com.quorum.timetableservice.entity.RoundView;
import com.quorum.timetableservice.entity.SessionView;
import com.quorum.timetableservice.event.EventFields;
import com.quorum.timetableservice.event.EventTypes;
import com.quorum.timetableservice.event.InvalidEventException;
import com.quorum.timetableservice.repository.RoundViewRepository;
import com.quorum.timetableservice.repository.SessionViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.LocalTime;
import java.util.Set;

/** drive-events: keeps session_view (upsert by sessionId) and round_view. */
@Service
public class SessionViewService {

    private static final Set<String> STATUSES = Set.of("SCHEDULED", "CANCELLED");

    private final SessionViewRepository sessions;
    private final RoundViewRepository rounds;
    private final Clock clock;

    public SessionViewService(SessionViewRepository sessions, RoundViewRepository rounds, Clock clock) {
        this.sessions = sessions;
        this.rounds = rounds;
        this.clock = clock;
    }

    /** @return the session's roundId */
    @Transactional(propagation = Propagation.MANDATORY)
    public long upsertSession(String eventId, String eventType, JsonNode e) {
        long sessionId = EventFields.id(e, "sessionId");
        long roundId = EventFields.id(e, "roundId");
        LocalTime start = EventFields.time(e, "startTime");
        LocalTime end = EventFields.time(e, "endTime");
        if (!start.isBefore(end)) {
            throw new InvalidEventException("startTime must be before endTime");
        }
        SessionView view = sessions.findById(sessionId).orElseGet(() -> {
            SessionView v = new SessionView();
            v.setSessionId(sessionId);
            return v;
        });
        view.setRoundId(roundId);
        view.setDriveId(EventFields.id(e, "driveId"));
        view.setDate(EventFields.date(e, "date"));
        view.setStartTime(start);
        view.setEndTime(end);
        view.setRoom(EventFields.optionalText(e, "room"));
        view.setStatus(status(eventType, e));
        view.setLastEventId(eventId);
        view.setUpdatedAt(clock.instant());
        sessions.save(view);
        return roundId;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void upsertRound(JsonNode e) {
        long roundId = EventFields.id(e, "roundId");
        RoundView view = rounds.findById(roundId).orElseGet(() -> {
            RoundView v = new RoundView();
            v.setRoundId(roundId);
            return v;
        });
        view.setDriveId(EventFields.id(e, "driveId"));
        view.setName(EventFields.optionalText(e, "name"));
        JsonNode sequence = e.get("sequence");
        view.setSequence(sequence != null && sequence.isIntegralNumber() ? sequence.asInt() : null);
        rounds.save(view);
    }

    private static String status(String eventType, JsonNode e) {
        String status = EventFields.optionalText(e, "status");
        if (status == null) {
            status = EventTypes.SESSION_CANCELLED.equals(eventType) ? "CANCELLED" : "SCHEDULED";
        }
        if (!STATUSES.contains(status)) {
            throw new InvalidEventException("Unknown session status " + status);
        }
        return status;
    }
}
