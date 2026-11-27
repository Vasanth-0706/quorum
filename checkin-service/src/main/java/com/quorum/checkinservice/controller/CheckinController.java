package com.quorum.checkinservice.controller;

import com.quorum.checkinservice.config.WebConfig;
import com.quorum.checkinservice.dto.ErrorResponse;
import com.quorum.checkinservice.dto.TapRequest;
import com.quorum.checkinservice.dto.TapResponse;
import com.quorum.checkinservice.service.CheckinService;
import com.quorum.checkinservice.service.CheckinService.TapOutcome;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The RFID reader endpoint. Authenticated by X-Device-Key, not by the X-User headers. */
@RestController
public class CheckinController {

    public static final String DEVICE_KEY_HEADER = "X-Device-Key";

    private final CheckinService checkins;

    public CheckinController(CheckinService checkins) {
        this.checkins = checkins;
    }

    @PostMapping(WebConfig.TAP_PATH)
    public ResponseEntity<?> tap(@RequestHeader(value = DEVICE_KEY_HEADER, required = false) String deviceKey,
                                 @Valid @RequestBody TapRequest request) {
        TapOutcome o = checkins.tap(deviceKey, request.cardUid(), request.tappedAt());
        return switch (o.result()) {
            case MARKED, ALREADY_MARKED -> ResponseEntity.ok(new TapResponse(o.result().name(), o.cardUid(),
                    o.studentId(), o.session().getSessionId(), o.session().getRoundId(), o.session().getRoom(),
                    o.device().getDeviceId(), o.mark().getMarkId(), o.mark().getMarkedAt(), o.tappedAt()));
            case UNKNOWN_CARD -> error(HttpStatus.NOT_FOUND, o,
                    "Card " + o.cardUid() + " is not enrolled; it is listed in GET /cards/unknown-taps");
            case NO_ACTIVE_SESSION -> error(HttpStatus.CONFLICT, o,
                    "No session is running in room " + o.device().getRoom() + " at " + o.tappedAt());
            case SESSION_CANCELLED -> error(HttpStatus.CONFLICT, o,
                    "Session " + o.session().getSessionId() + " in room " + o.device().getRoom()
                            + " is cancelled and takes no new taps");
        };
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, TapOutcome o, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(), status.getReasonPhrase(),
                o.result().name(), message, List.of()));
    }
}
