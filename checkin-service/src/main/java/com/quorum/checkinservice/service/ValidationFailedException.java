package com.quorum.checkinservice.service;

import com.quorum.checkinservice.dto.ErrorResponse.FieldErrorItem;
import org.springframework.http.HttpStatus;

import java.util.List;

/** 400 with a list of problems, e.g. the bad lines of a CSV. */
public class ValidationFailedException extends ApiException {

    private final List<FieldErrorItem> errors;

    public ValidationFailedException(String code, String message, List<FieldErrorItem> errors) {
        super(HttpStatus.BAD_REQUEST, code, message);
        this.errors = List.copyOf(errors);
    }

    public List<FieldErrorItem> getErrors() {
        return errors;
    }
}
