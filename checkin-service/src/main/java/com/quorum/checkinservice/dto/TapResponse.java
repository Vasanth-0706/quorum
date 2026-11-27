package com.quorum.checkinservice.dto;

import java.time.Instant;

/** 200 answer of a tap: result MARKED (new mark) or ALREADY_MARKED (no new mark). */
public record TapResponse(String result, String cardUid, String studentId, long sessionId, long roundId,
                          String room, String deviceId, String markId, Instant markedAt, Instant tappedAt) {
}
