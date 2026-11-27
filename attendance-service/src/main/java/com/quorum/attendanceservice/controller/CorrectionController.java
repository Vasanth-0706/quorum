package com.quorum.attendanceservice.controller;

import com.quorum.attendanceservice.config.TemporaryAuthInterceptor;
import com.quorum.attendanceservice.dto.CorrectionRequest;
import com.quorum.attendanceservice.dto.CorrectionResponse;
import com.quorum.attendanceservice.service.CorrectionService;
import com.quorum.attendanceservice.service.WriteLock;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CorrectionController {

    private final CorrectionService corrections;
    private final WriteLock lock;

    public CorrectionController(CorrectionService corrections, WriteLock lock) {
        this.corrections = corrections;
        this.lock = lock;
    }

    /** The TEMPORARY auth interceptor has already checked both headers. */
    @PostMapping("/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    public CorrectionResponse correct(@Valid @RequestBody CorrectionRequest request,
                                      @RequestHeader(TemporaryAuthInterceptor.USER_ID_HEADER) String userId,
                                      @RequestHeader(TemporaryAuthInterceptor.USER_ROLE_HEADER) String role) {
        return lock.call(() -> corrections.correct(request, userId.trim(), role));
    }

    /** Newest first. */
    @GetMapping("/corrections")
    public List<CorrectionResponse> list(@RequestParam @Positive Long sessionId) {
        return corrections.forSession(sessionId);
    }
}
