package com.quorum.checkinservice.service;

import com.quorum.checkinservice.entity.Mark.Source;
import com.quorum.checkinservice.entity.PendingFileUpload;
import com.quorum.checkinservice.entity.SessionView;
import com.quorum.checkinservice.repository.PendingFileUploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * import-events ATTENDANCE_UPLOADED → FILE marks (present) for the listed students of the session.
 *
 * <ul>
 *   <li>markId is a name-based UUID of {@code FILE:<uploadId>:<studentId>}, so the same upload never
 *       stores a mark twice, even if its event were applied again.</li>
 *   <li>A student whose latest mark is already present gets no new mark (nothing would change).</li>
 *   <li>Students not listed are left as they are.</li>
 *   <li>A CANCELLED session keeps what it had and takes no new marks: the upload is skipped.</li>
 *   <li>A session not known yet: the upload waits in pending_file_upload and is applied when the
 *       session arrives on drive-events.</li>
 * </ul>
 * markedAt is the time the marks are stored, because import-service's uploadedAt has no offset.
 */
@Service
public class FileMarkService {

    public static final String MARKED_BY = "import-service";
    private static final Logger log = LoggerFactory.getLogger(FileMarkService.class);

    private final MarkService marks;
    private final PendingFileUploadRepository pending;
    private final Clock clock;

    public FileMarkService(MarkService marks, PendingFileUploadRepository pending, Clock clock) {
        this.marks = marks;
        this.pending = pending;
        this.clock = clock;
    }

    /** @return how many marks were stored now (0 if the upload is parked or skipped) */
    @Transactional(propagation = Propagation.MANDATORY)
    public int apply(String uploadId, SessionView session, Collection<String> studentIds) {
        if (SessionMatcher.CANCELLED.equals(session.getStatus())) {
            log.warn("Upload {} for cancelled session {} skipped: a cancelled session takes no new marks",
                    uploadId, session.getSessionId());
            return 0;
        }
        Instant now = clock.instant();
        int stored = 0;
        for (String studentId : studentIds) {
            String markId = markId(uploadId, studentId);
            if (marks.exists(markId)) {
                continue;
            }
            boolean alreadyPresent = marks.latest(session.getSessionId(), studentId).map(m -> m.isPresent()).orElse(false);
            if (alreadyPresent) {
                continue;
            }
            marks.record(markId, session, studentId, true, Source.FILE, MARKED_BY, now);
            stored++;
        }
        log.info("Upload {} for session {}: {} of {} students got a FILE mark", uploadId, session.getSessionId(),
                stored, studentIds.size());
        return stored;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void park(String eventId, String uploadId, long sessionId, Collection<String> studentIds) {
        pending.save(new PendingFileUpload(eventId, uploadId, sessionId, String.join(",", studentIds), clock.instant()));
        log.info("Upload {} waits for session {} (not received from drive-events yet)", uploadId, sessionId);
    }

    /** Applies and deletes the uploads that were waiting for this session. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int applyPending(SessionView session) {
        int stored = 0;
        for (PendingFileUpload p : pending.findBySessionIdOrderByReceivedAtAsc(session.getSessionId())) {
            List<String> ids = p.getStudentIds().isEmpty() ? List.of() : Arrays.asList(p.getStudentIds().split(","));
            stored += apply(p.getUploadId(), session, ids);
            pending.delete(p);
        }
        return stored;
    }

    static String markId(String uploadId, String studentId) {
        return UUID.nameUUIDFromBytes(("FILE:" + uploadId + ":" + studentId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
