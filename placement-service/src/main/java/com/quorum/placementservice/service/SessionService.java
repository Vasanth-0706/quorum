package com.quorum.placementservice.service;

import com.quorum.placementservice.config.Topics;
import com.quorum.placementservice.dto.SessionRequest;
import com.quorum.placementservice.dto.SessionResponse;
import com.quorum.placementservice.entity.Round;
import com.quorum.placementservice.entity.Session;
import com.quorum.placementservice.entity.SessionStatus;
import com.quorum.placementservice.event.DriveEventFactory;
import com.quorum.placementservice.event.EventTypes;
import com.quorum.placementservice.repository.RoundRepository;
import com.quorum.placementservice.repository.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Session rules:
 * <ul>
 *   <li>A new session starts SCHEDULED; its date may differ from the drive date.</li>
 *   <li>A cancelled session cannot be rescheduled or cancelled again (409).</li>
 *   <li>A reschedule that changes nothing writes no event.</li>
 *   <li>Every change writes its outbox row in the same transaction, keyed by driveId.</li>
 * </ul>
 */
@Service
public class SessionService {

    private final RoundRepository roundRepository;
    private final SessionRepository sessionRepository;
    private final OutboxService outbox;
    private final DriveEventFactory events;

    public SessionService(RoundRepository roundRepository, SessionRepository sessionRepository,
                          OutboxService outbox, DriveEventFactory events) {
        this.roundRepository = roundRepository;
        this.sessionRepository = sessionRepository;
        this.outbox = outbox;
        this.events = events;
    }

    @Transactional
    public SessionResponse create(Long roundId, SessionRequest request) {
        Round round = roundRepository.findById(roundId)
                .orElseThrow(() -> new NotFoundException("Round " + roundId + " not found"));
        Session session = new Session();
        session.setRound(round);
        session.setStatus(SessionStatus.SCHEDULED);
        apply(session, request);
        Session saved = sessionRepository.save(session);
        publish(EventTypes.SESSION_SCHEDULED, saved);
        return SessionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> list(Long roundId) {
        if (!roundRepository.existsById(roundId)) {
            throw new NotFoundException("Round " + roundId + " not found");
        }
        return sessionRepository.findByRoundIdOrderByDateAscStartTimeAscIdAsc(roundId).stream()
                .map(SessionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public SessionResponse get(Long id) {
        return SessionResponse.from(require(id));
    }

    @Transactional
    public SessionResponse reschedule(Long id, SessionRequest request) {
        Session session = require(id);
        if (session.isCancelled()) {
            throw new ConflictException("Session " + id + " is cancelled and cannot be rescheduled");
        }
        if (sameAs(session, request)) {
            return SessionResponse.from(session);
        }
        apply(session, request);
        publish(EventTypes.SESSION_UPDATED, sessionRepository.save(session));
        return SessionResponse.from(session);
    }

    @Transactional
    public SessionResponse cancel(Long id) {
        Session session = require(id);
        if (session.isCancelled()) {
            throw new ConflictException("Session " + id + " is already cancelled");
        }
        session.setStatus(SessionStatus.CANCELLED);
        publish(EventTypes.SESSION_CANCELLED, sessionRepository.save(session));
        return SessionResponse.from(session);
    }

    private Session require(Long id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Session " + id + " not found"));
    }

    private void publish(String eventType, Session session) {
        String driveId = String.valueOf(session.getRound().getDrive().getId());
        outbox.append(Topics.DRIVE_EVENTS, driveId, eventType, events.session(eventType, session));
    }

    private static void apply(Session session, SessionRequest request) {
        session.setDate(request.date());
        session.setStartTime(request.startTime().withNano(0));
        session.setEndTime(request.endTime().withNano(0));
        session.setRoom(request.room().trim());
    }

    private static boolean sameAs(Session s, SessionRequest r) {
        return Objects.equals(s.getDate(), r.date())
                && Objects.equals(s.getStartTime(), r.startTime().withNano(0))
                && Objects.equals(s.getEndTime(), r.endTime().withNano(0))
                && Objects.equals(s.getRoom(), r.room().trim());
    }
}
