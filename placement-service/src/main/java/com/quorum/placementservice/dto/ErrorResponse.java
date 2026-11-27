package com.quorum.placementservice.dto;

import java.util.List;

/** Body of every 4xx/5xx answer. {@code errors} lists field problems for a 400, otherwise it is empty. */
public record ErrorResponse(int status, String error, String message, List<FieldErrorItem> errors) {

    public record FieldErrorItem(String field, String message) {
    }
}
