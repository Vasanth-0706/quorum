package com.quorum.checkinservice.dto;

import com.quorum.checkinservice.entity.Card;

import java.time.Instant;

public record CardResponse(String cardUid, String studentId, String enrolledBy, Instant enrolledAt) {

    public static CardResponse from(Card c) {
        return new CardResponse(c.getCardUid(), c.getStudentId(), c.getEnrolledBy(), c.getEnrolledAt());
    }
}
