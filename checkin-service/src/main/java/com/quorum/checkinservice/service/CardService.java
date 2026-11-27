package com.quorum.checkinservice.service;

import com.quorum.checkinservice.dto.ErrorResponse.FieldErrorItem;
import com.quorum.checkinservice.entity.Card;
import com.quorum.checkinservice.entity.Device;
import com.quorum.checkinservice.entity.UnknownTap;
import com.quorum.checkinservice.repository.CardRepository;
import com.quorum.checkinservice.repository.UnknownTapRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Card ↔ student enrolment, CSV import and the list of unknown taps waiting for enrolment. */
@Service
public class CardService {

    public record Enrolled(Card card, boolean created) {
    }

    public record Imported(int imported, int unchanged, int total) {
    }

    private static final Logger log = LoggerFactory.getLogger(CardService.class);
    static final int MAX_IMPORT_ROWS = 20_000;

    private final CardRepository cards;
    private final UnknownTapRepository unknownTaps;
    private final Clock clock;

    public CardService(CardRepository cards, UnknownTapRepository unknownTaps, Clock clock) {
        this.cards = cards;
        this.unknownTaps = unknownTaps;
        this.clock = clock;
    }

    /**
     * Enrols one card. The same card for the same student again is a no-op (created = false);
     * a card already enrolled to another student is 409 CARD_IN_USE.
     */
    @Transactional
    public Enrolled enrol(String studentId, String rawUid, String actor) {
        if (!StudentIds.valid(studentId)) {
            throw ApiException.badRequest("INVALID_STUDENT_ID", "studentId must be exactly 10 digits");
        }
        String uid = CardUids.normalize(rawUid);
        Optional<Card> existing = cards.findById(uid);
        if (existing.isPresent()) {
            if (!existing.get().getStudentId().equals(studentId)) {
                throw ApiException.conflict("CARD_IN_USE",
                        "Card " + uid + " is already enrolled to student " + existing.get().getStudentId());
            }
            return new Enrolled(existing.get(), false);
        }
        Card card = cards.save(new Card(uid, studentId, actor, clock.instant()));
        unknownTaps.deleteById(uid);
        log.info("Card {} enrolled to {} by {}", uid, studentId, actor);
        return new Enrolled(card, true);
    }

    /**
     * CSV with lines {@code studentId,cardUid} (a header line naming studentId is skipped, blank
     * lines too). All or nothing: if any line is wrong, nothing is stored and every bad line is listed.
     */
    @Transactional
    public Imported importCsv(String csv, String actor) {
        List<FieldErrorItem> errors = new ArrayList<>();
        Map<String, String> byUid = new LinkedHashMap<>();
        String[] lines = csv.replace("﻿", "").split("\r\n|\r|\n");
        int rows = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            String where = "line " + (i + 1);
            if (line.isEmpty() || (i == 0 && line.toLowerCase(Locale.ROOT).contains("studentid"))) {
                continue;
            }
            if (++rows > MAX_IMPORT_ROWS) {
                errors.add(new FieldErrorItem(where, "more than " + MAX_IMPORT_ROWS + " rows"));
                break;
            }
            String[] cols = line.split(",", -1);
            if (cols.length != 2) {
                errors.add(new FieldErrorItem(where, "expected 2 columns studentId,cardUid"));
                continue;
            }
            String studentId = unquote(cols[0]);
            String uid;
            try {
                uid = CardUids.normalize(unquote(cols[1]));
            } catch (ApiException e) {
                errors.add(new FieldErrorItem(where, e.getMessage()));
                continue;
            }
            if (!StudentIds.valid(studentId)) {
                errors.add(new FieldErrorItem(where, "studentId must be exactly 10 digits: " + studentId));
                continue;
            }
            String before = byUid.putIfAbsent(uid, studentId);
            if (before != null && !before.equals(studentId)) {
                errors.add(new FieldErrorItem(where, "card " + uid + " appears earlier for student " + before));
                continue;
            }
            Optional<Card> existing = cards.findById(uid);
            if (existing.isPresent() && !existing.get().getStudentId().equals(studentId)) {
                errors.add(new FieldErrorItem(where,
                        "card " + uid + " is already enrolled to student " + existing.get().getStudentId()));
            }
        }
        if (byUid.isEmpty() && errors.isEmpty()) {
            errors.add(new FieldErrorItem("file", "no studentId,cardUid rows"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException("CSV_INVALID", errors.size() + " line(s) are wrong; nothing was imported", errors);
        }

        Instant now = clock.instant();
        int imported = 0;
        for (Map.Entry<String, String> e : byUid.entrySet()) {
            if (cards.existsById(e.getKey())) {
                continue;
            }
            cards.save(new Card(e.getKey(), e.getValue(), actor, now));
            unknownTaps.deleteById(e.getKey());
            imported++;
        }
        log.info("Card import by {}: {} new, {} already enrolled", actor, imported, byUid.size() - imported);
        return new Imported(imported, byUid.size() - imported, byUid.size());
    }

    @Transactional(readOnly = true)
    public Optional<Card> find(String uid) {
        return cards.findById(uid);
    }

    @Transactional(readOnly = true)
    public List<Card> list(String studentId) {
        return studentId == null ? cards.findAllByOrderByStudentIdAscCardUidAsc()
                : cards.findByStudentIdOrderByCardUidAsc(studentId);
    }

    /** Remembers a tapped card nobody has enrolled yet. Runs inside the tap's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUnknown(String uid, Instant at, Device device) {
        unknownTaps.findById(uid).ifPresentOrElse(
                t -> t.seenAgain(at, device.getDeviceId(), device.getRoom()),
                () -> unknownTaps.save(new UnknownTap(uid, at, device.getDeviceId(), device.getRoom())));
    }

    @Transactional(readOnly = true)
    public List<UnknownTap> unknownTaps() {
        return unknownTaps.findAllByOrderByLastSeenAtDesc();
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }
}
