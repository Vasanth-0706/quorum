package com.quorum.timetableservice.service;

/** 401, TEMPORARY auth headers are missing. */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
