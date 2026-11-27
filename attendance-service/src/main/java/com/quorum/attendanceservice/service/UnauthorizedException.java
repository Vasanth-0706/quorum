package com.quorum.attendanceservice.service;

/** 401, TEMPORARY auth headers are missing. */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
