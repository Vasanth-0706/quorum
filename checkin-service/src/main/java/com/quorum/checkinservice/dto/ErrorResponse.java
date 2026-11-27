package com.quorum.checkinservice.dto;

import java.util.List;

/**
 * Body of every 4xx/5xx answer: the same shape as the other services plus {@code code}, a stable
 * name a reader can switch on (UNKNOWN_CARD, NO_ACTIVE_SESSION ...). {@code errors} lists field
 * problems for a 400, otherwise it is empty.
 */
public record ErrorResponse(int status, String error, String code, String message, List<FieldErrorItem> errors) {

    public record FieldErrorItem(String field, String message) {
    }
}
