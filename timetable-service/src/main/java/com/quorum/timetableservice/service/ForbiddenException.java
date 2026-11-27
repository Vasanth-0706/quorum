package com.quorum.timetableservice.service;

/** 403, TEMPORARY auth role is not allowed. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
