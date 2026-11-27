package com.quorum.placementservice.controller;

import com.quorum.placementservice.dto.SessionRequest;
import com.quorum.placementservice.dto.SessionResponse;
import com.quorum.placementservice.service.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/rounds/{roundId}/sessions")
    public ResponseEntity<SessionResponse> createSession(@PathVariable Long roundId,
                                                         @Valid @RequestBody SessionRequest request) {
        SessionResponse created = sessionService.create(roundId, request);
        return ResponseEntity.created(URI.create("/sessions/" + created.id())).body(created);
    }

    @GetMapping("/rounds/{roundId}/sessions")
    public List<SessionResponse> listSessions(@PathVariable Long roundId) {
        return sessionService.list(roundId);
    }

    @GetMapping("/sessions/{id}")
    public SessionResponse getSession(@PathVariable Long id) {
        return sessionService.get(id);
    }

    /** Reschedule: new date, times and/or room. */
    @PutMapping("/sessions/{id}")
    public SessionResponse reschedule(@PathVariable Long id, @Valid @RequestBody SessionRequest request) {
        return sessionService.reschedule(id, request);
    }

    @PostMapping("/sessions/{id}/cancel")
    public SessionResponse cancel(@PathVariable Long id) {
        return sessionService.cancel(id);
    }
}
