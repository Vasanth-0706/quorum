package com.quorum.attendanceservice.service;

import com.quorum.attendanceservice.dto.CorrectionRequest;
import com.quorum.attendanceservice.dto.CorrectionResponse;
import com.quorum.attendanceservice.entity.AttendanceMark;
import com.quorum.attendanceservice.entity.AttendanceMark.Source;
import com.quorum.attendanceservice.entity.Correction;
import com.quorum.attendanceservice.entity.SessionView;
import com.quorum.attendanceservice.repository.CorrectionRepository;
import com.quorum.attendanceservice.repository.SessionViewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REQ-F12, no approval step: a COORDINATOR may correct a mark until session date + 14 days
 * (inclusive), a DIRECTOR at any time. The correction is a new versioned mark that becomes the
 * latest one, plus a correction row with the old value, actor, time and reason; OD is recomputed
 * in the same transaction. "Today" is read from the injected Clock (zone quorum.time-zone).
 */
@Service
public class CorrectionService {

    public static final String COORDINATOR = "COORDINATOR";
    public static final String DIRECTOR = "DIRECTOR";
    public static final int COORDINATOR_WINDOW_DAYS = 14;

    private static final Logger log = LoggerFactory.getLogger(CorrectionService.class);

    private final SessionViewRepository sessions;
    private final CorrectionRepository corrections;
    private final MarkService marks;
    private final Clock clock;

    public CorrectionService(SessionViewRepository sessions, CorrectionRepository corrections, MarkService marks,
                             Clock clock) {
        this.sessions = sessions;
        this.corrections = corrections;
        this.marks = marks;
        this.clock = clock;
    }

    @Transactional
    public CorrectionResponse correct(CorrectionRequest request, String actorId, String role) {
        SessionView session = sessions.findById(request.sessionId()).orElseThrow(() -> new NotFoundException(
                "Session " + request.sessionId() + " is not known to attendance-service"));
        checkWindow(session.getDate(), role);

        Boolean oldPresent = marks.latest(session.getSessionId(), request.studentId())
                .map(AttendanceMark::isPresent)
                .orElse(null);
        Instant now = clock.instant();
        AttendanceMark mark = marks.record("CORRECTION-" + UUID.randomUUID(), session.getSessionId(),
                        request.studentId(), request.present(), Source.CORRECTION, actorId, now, null)
                .orElseThrow();
        Correction saved = corrections.save(new Correction(session.getSessionId(), request.studentId(), oldPresent,
                request.present(), request.reason().trim(), actorId, role, now, mark.getMarkId(), mark.getVersion()));
        log.info("{} {} corrected {} in session {}: {} -> {}", role, actorId, request.studentId(),
                session.getSessionId(), oldPresent, request.present());
        return CorrectionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<CorrectionResponse> forSession(long sessionId) {
        return corrections.findBySessionIdOrderByIdDesc(sessionId).stream().map(CorrectionResponse::from).toList();
    }

    private void checkWindow(LocalDate sessionDate, String role) {
        if (DIRECTOR.equals(role)) {
            return;
        }
        if (!COORDINATOR.equals(role)) {
            throw new ForbiddenException("X-User-Role must be COORDINATOR or DIRECTOR");
        }
        LocalDate lastDay = sessionDate.plusDays(COORDINATOR_WINDOW_DAYS);
        LocalDate today = LocalDate.now(clock);
        if (today.isAfter(lastDay)) {
            throw new ForbiddenException("A COORDINATOR may correct attendance only up to " + COORDINATOR_WINDOW_DAYS
                    + " days after the session date (session on " + sessionDate + ", last day " + lastDay
                    + ", today " + today + "). Ask a DIRECTOR.");
        }
    }
}
