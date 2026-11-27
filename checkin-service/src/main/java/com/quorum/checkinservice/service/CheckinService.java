package com.quorum.checkinservice.service;

import com.quorum.checkinservice.entity.Card;
import com.quorum.checkinservice.entity.Device;
import com.quorum.checkinservice.entity.Mark;
import com.quorum.checkinservice.entity.Mark.Source;
import com.quorum.checkinservice.entity.SessionView;
import com.quorum.checkinservice.repository.DeviceRepository;
import com.quorum.checkinservice.repository.SessionViewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Optional;

/** RFID taps and manual marks. Every write runs under {@link WriteLock}, its transaction inside. */
@Service
public class CheckinService {

    public enum TapResult { MARKED, ALREADY_MARKED, UNKNOWN_CARD, NO_ACTIVE_SESSION, SESSION_CANCELLED }

    /** What a tap did. session, studentId and mark are null when they do not apply. */
    public record TapOutcome(TapResult result, Device device, String cardUid, String studentId,
                             SessionView session, Mark mark, Instant tappedAt) {
    }

    public record ManualOutcome(Mark mark, boolean created) {
    }

    private static final Logger log = LoggerFactory.getLogger(CheckinService.class);

    private final DeviceService devices;
    private final DeviceRepository deviceRows;
    private final CardService cards;
    private final MarkService marks;
    private final SessionViewRepository sessions;
    private final WriteLock lock;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration maxSkew;

    public CheckinService(DeviceService devices, DeviceRepository deviceRows, CardService cards, MarkService marks,
                          SessionViewRepository sessions, WriteLock lock, PlatformTransactionManager txManager,
                          Clock clock, @Value("${quorum.checkin.max-clock-skew:5m}") Duration maxSkew) {
        this.devices = devices;
        this.deviceRows = deviceRows;
        this.cards = cards;
        this.marks = marks;
        this.sessions = sessions;
        this.lock = lock;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.maxSkew = maxSkew;
    }

    /**
     * A reader tapped a card. The tap time is the reader's tappedAt if given (within the allowed
     * clock skew), else now; it is matched in the clock's zone (quorum.time-zone) against the
     * sessions of the device's room on that local date.
     */
    public TapOutcome tap(String deviceKey, String rawCardUid, OffsetDateTime tappedAt) {
        long started = System.nanoTime();
        Device device = devices.authenticate(deviceKey);
        String uid = CardUids.normalize(rawCardUid);
        Instant at = tapTime(tappedAt);
        TapOutcome outcome = lock.call(() -> tx.execute(status -> doTap(device, uid, at)));
        log.info("Tap {} on {} ({}) -> {}{} in {} ms", uid, device.getDeviceId(), device.getRoom(), outcome.result(),
                outcome.session() != null ? " session " + outcome.session().getSessionId() : "",
                (System.nanoTime() - started) / 1_000_000);
        return outcome;
    }

    private TapOutcome doTap(Device device, String uid, Instant at) {
        deviceRows.touch(device.getDeviceId(), at);
        Optional<Card> card = cards.find(uid);
        if (card.isEmpty()) {
            cards.recordUnknown(uid, at, device);
            return new TapOutcome(TapResult.UNKNOWN_CARD, device, uid, null, null, null, at);
        }
        String studentId = card.get().getStudentId();
        ZonedDateTime local = at.atZone(clock.getZone());
        SessionMatcher.Match match = SessionMatcher.match(
                sessions.findInRoomOnDate(device.getRoom(), local.toLocalDate()), local.toLocalTime());
        if (match.running().isEmpty()) {
            TapResult result = match.cancelled().isPresent() ? TapResult.SESSION_CANCELLED : TapResult.NO_ACTIVE_SESSION;
            return new TapOutcome(result, device, uid, studentId, match.cancelled().orElse(null), null, at);
        }
        SessionView session = match.running().get();
        Optional<Mark> latest = marks.latest(session.getSessionId(), studentId);
        // A second tap never adds a mark. Neither does a tap after a coordinator marked the student
        // absent following an earlier tap: the reader must not undo a manual decision.
        if (latest.isPresent() && (latest.get().isPresent()
                || marks.hasMark(session.getSessionId(), studentId, Source.RFID))) {
            return new TapOutcome(TapResult.ALREADY_MARKED, device, uid, studentId, session, latest.get(), at);
        }
        Mark mark = marks.record(session, studentId, true, Source.RFID, device.getDeviceId(), at);
        return new TapOutcome(TapResult.MARKED, device, uid, studentId, session, mark, at);
    }

    private Instant tapTime(OffsetDateTime tappedAt) {
        Instant now = clock.instant();
        if (tappedAt == null) {
            return now;
        }
        Instant at = tappedAt.toInstant();
        if (Duration.between(at, now).abs().compareTo(maxSkew) > 0) {
            throw ApiException.badRequest("TAPPED_AT_OUT_OF_RANGE",
                    "tappedAt is more than " + maxSkew.toMinutes() + " min away from the server time " + now);
        }
        return at;
    }

    /**
     * A coordinator or director marks a student present or absent. If the latest mark already says
     * the same, nothing is stored (created = false) and that mark is returned.
     */
    public ManualOutcome manual(long sessionId, String studentId, boolean present, String actor) {
        return lock.call(() -> tx.execute(status -> {
            SessionView session = sessions.findById(sessionId).orElseThrow(() -> ApiException.notFound(
                    "SESSION_NOT_FOUND", "Session " + sessionId + " is not known to checkin-service"));
            if (SessionMatcher.CANCELLED.equals(session.getStatus())) {
                throw ApiException.conflict("SESSION_CANCELLED",
                        "Session " + sessionId + " is cancelled; it keeps its marks but takes no new ones");
            }
            Optional<Mark> latest = marks.latest(sessionId, studentId);
            if (latest.isPresent() && latest.get().isPresent() == present) {
                return new ManualOutcome(latest.get(), false);
            }
            Mark mark = marks.record(session, studentId, present, Source.MANUAL, actor, clock.instant());
            log.info("Manual mark {} {} in session {} by {}", studentId, present ? "present" : "absent", sessionId, actor);
            return new ManualOutcome(mark, true);
        }));
    }
}
