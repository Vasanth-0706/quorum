package com.quorum.checkinservice.controller;

import com.quorum.checkinservice.config.TemporaryAuthInterceptor;
import com.quorum.checkinservice.dto.ManualMarkRequest;
import com.quorum.checkinservice.dto.MarkResponse;
import com.quorum.checkinservice.dto.SessionResponse;
import com.quorum.checkinservice.service.CheckinService;
import com.quorum.checkinservice.service.MarkService;
import com.quorum.checkinservice.repository.SessionViewRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
public class MarkController {

    private final CheckinService checkins;
    private final MarkService marks;
    private final SessionViewRepository sessions;

    public MarkController(CheckinService checkins, MarkService marks, SessionViewRepository sessions) {
        this.checkins = checkins;
        this.marks = marks;
        this.sessions = sessions;
    }

    /** 201 with the new mark, or 200 with the latest mark when it already says the same. */
    @PostMapping("/sessions/{sessionId}/marks")
    public ResponseEntity<MarkResponse> manual(@PathVariable @Positive long sessionId,
                                               @RequestHeader(TemporaryAuthInterceptor.USER_ID_HEADER) String userId,
                                               @Valid @RequestBody ManualMarkRequest request) {
        CheckinService.ManualOutcome o = checkins.manual(sessionId, request.studentId(), request.present(), userId);
        return ResponseEntity.status(o.created() ? HttpStatus.CREATED : HttpStatus.OK).body(MarkResponse.from(o.mark()));
    }

    /** The latest mark per student; history=true lists every version. */
    @GetMapping("/sessions/{sessionId}/marks")
    public List<MarkResponse> marks(@PathVariable @Positive long sessionId,
                                    @RequestParam(defaultValue = "false") boolean history) {
        return marks.ofSession(sessionId, history).stream().map(MarkResponse::from).toList();
    }

    /** Sessions known from drive-events, optionally of one date. */
    @GetMapping("/sessions")
    @Transactional(readOnly = true)
    public List<SessionResponse> sessions(@RequestParam(required = false)
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return (date == null ? sessions.findAllByOrderByDateAscStartTimeAscSessionIdAsc()
                : sessions.findByDateOrderByStartTimeAscSessionIdAsc(date))
                .stream().map(SessionResponse::from).toList();
    }
}
