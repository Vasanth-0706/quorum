package com.quorum.timetableservice.service;

import com.quorum.timetableservice.entity.Enrolment;
import com.quorum.timetableservice.entity.Semester;
import com.quorum.timetableservice.entity.Slot;
import com.quorum.timetableservice.entity.TimetablePart;
import com.quorum.timetableservice.entity.TimetableUpload;
import com.quorum.timetableservice.event.EventFields;
import com.quorum.timetableservice.event.InvalidEventException;
import com.quorum.timetableservice.repository.EnrolmentRepository;
import com.quorum.timetableservice.repository.SemesterRepository;
import com.quorum.timetableservice.repository.SlotRepository;
import com.quorum.timetableservice.repository.TimetablePartRepository;
import com.quorum.timetableservice.repository.TimetableUploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TIMETABLE_UPLOADED arrives in parts sharing an uploadId: slots only in part 1, enrolments
 * in every part. Parts are buffered in timetable_part; when the last one arrives the stored
 * timetable of that semester is replaced in the same transaction and the semester becomes active.
 */
@Service
public class TimetableIngestService {

    public enum Outcome { BUFFERED, APPLIED, DUPLICATE_PART }

    record SlotData(String sectionCode, String courseName, DayOfWeek dayOfWeek, LocalTime start,
                    LocalTime end, String room) {
    }

    record EnrolmentData(String studentId, String sectionCode) {
    }

    private static final Logger log = LoggerFactory.getLogger(TimetableIngestService.class);
    private static final int MAX_PARTS = 10_000;

    private final TimetableUploadRepository uploads;
    private final TimetablePartRepository parts;
    private final SemesterRepository semesters;
    private final SlotRepository slots;
    private final EnrolmentRepository enrolments;
    private final ObjectMapper json;
    private final Clock clock;

    public TimetableIngestService(TimetableUploadRepository uploads, TimetablePartRepository parts,
                                  SemesterRepository semesters, SlotRepository slots, EnrolmentRepository enrolments,
                                  ObjectMapper json, Clock clock) {
        this.uploads = uploads;
        this.parts = parts;
        this.semesters = semesters;
        this.slots = slots;
        this.enrolments = enrolments;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome onPart(JsonNode e) {
        String uploadId = EventFields.text(e, "uploadId", 64);
        String semester = EventFields.text(e, "semester", 40);
        int part = EventFields.integer(e, "part");
        int totalParts = EventFields.integer(e, "totalParts");
        if (totalParts < 1 || totalParts > MAX_PARTS || part < 1 || part > totalParts) {
            throw new InvalidEventException("part " + part + " of " + totalParts + " is not valid");
        }
        JsonNode slotArray = EventFields.array(e, "slots");
        JsonNode enrolmentArray = EventFields.array(e, "enrolments");
        // Validate now, so a bad part goes to the DLT on arrival instead of blocking the upload later
        parseSlots(slotArray);
        parseEnrolments(enrolmentArray);

        Instant now = clock.instant();
        TimetableUpload upload = uploads.findById(uploadId).orElse(null);
        if (upload == null) {
            upload = uploads.save(new TimetableUpload(uploadId, semester, totalParts,
                    EventFields.optionalText(e, "uploadedAt"), now));
        } else if (!upload.getSemester().equals(semester) || upload.getTotalParts() != totalParts) {
            throw new InvalidEventException("Upload " + uploadId + " was " + upload.getSemester() + " in "
                    + upload.getTotalParts() + " parts, this part says " + semester + " in " + totalParts);
        }
        if (upload.getStatus() == TimetableUpload.Status.APPLIED || parts.existsByUploadIdAndPart(uploadId, part)) {
            log.info("Duplicate part {}/{} of upload {} ignored", part, totalParts, uploadId);
            return Outcome.DUPLICATE_PART;
        }
        parts.save(new TimetablePart(uploadId, part, slotArray.toString(), enrolmentArray.toString(), now));
        if (parts.countByUploadId(uploadId) < totalParts) {
            log.info("Buffered part {}/{} of upload {} ({})", part, totalParts, uploadId, semester);
            return Outcome.BUFFERED;
        }
        apply(upload, now);
        return Outcome.APPLIED;
    }

    private void apply(TimetableUpload upload, Instant now) {
        String semester = upload.getSemester();
        List<SlotData> slotData = new ArrayList<>();
        Map<EnrolmentData, Boolean> enrolmentData = new LinkedHashMap<>(); // de-duplicated, order kept
        for (TimetablePart p : parts.findByUploadIdOrderByPartAsc(upload.getUploadId())) {
            if (p.getPart() == 1) {
                slotData.addAll(parseSlots(json.readTree(p.getSlotsJson())));
            }
            parseEnrolments(json.readTree(p.getEnrolmentsJson())).forEach(d -> enrolmentData.put(d, true));
        }

        slots.deleteBySemester(semester);
        enrolments.deleteBySemester(semester);
        slots.saveAll(slotData.stream()
                .map(s -> new Slot(semester, s.sectionCode(), s.courseName(), s.dayOfWeek(), s.start(), s.end(), s.room()))
                .toList());
        enrolments.saveAll(enrolmentData.keySet().stream()
                .map(d -> new Enrolment(semester, d.studentId(), d.sectionCode()))
                .toList());

        semesters.deactivateAll();
        Semester row = semesters.findById(semester).orElseGet(() -> {
            Semester s = new Semester();
            s.setCode(semester);
            return s;
        });
        row.setActive(true);
        row.setUploadId(upload.getUploadId());
        row.setSlotCount(slotData.size());
        row.setEnrolmentCount(enrolmentData.size());
        row.setAppliedAt(now);
        semesters.save(row);

        upload = uploads.findById(upload.getUploadId()).orElseThrow();
        upload.setStatus(TimetableUpload.Status.APPLIED);
        upload.setAppliedAt(now);
        uploads.save(upload);
        parts.deleteByUploadId(upload.getUploadId());
        log.info("Applied timetable {} for {}: {} slots, {} enrolments; now the active semester",
                upload.getUploadId(), semester, slotData.size(), enrolmentData.size());
    }

    static List<SlotData> parseSlots(JsonNode array) {
        List<SlotData> out = new ArrayList<>();
        for (JsonNode s : array) {
            if (!s.isObject()) {
                throw new InvalidEventException("A slot is not an object");
            }
            DayOfWeek day;
            try {
                day = DayOfWeek.valueOf(EventFields.text(s, "dayOfWeek").toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new InvalidEventException("Unknown dayOfWeek " + s.get("dayOfWeek"), ex);
            }
            LocalTime start = EventFields.time(s, "startTime");
            LocalTime end = EventFields.time(s, "endTime");
            if (!start.isBefore(end)) {
                throw new InvalidEventException("Slot startTime must be before endTime");
            }
            out.add(new SlotData(EventFields.text(s, "sectionCode", 40), limit(EventFields.optionalText(s, "courseName"), 120),
                    day, start, end, limit(EventFields.optionalText(s, "room"), 60)));
        }
        return out;
    }

    static List<EnrolmentData> parseEnrolments(JsonNode array) {
        List<EnrolmentData> out = new ArrayList<>();
        for (JsonNode n : array) {
            if (!n.isObject()) {
                throw new InvalidEventException("An enrolment is not an object");
            }
            out.add(new EnrolmentData(EventFields.studentId(EventFields.text(n, "studentId")),
                    EventFields.text(n, "sectionCode", 40)));
        }
        return out;
    }

    private static String limit(String s, int max) {
        if (s != null && s.length() > max) {
            throw new InvalidEventException("Value longer than " + max + ": " + s);
        }
        return s;
    }
}
