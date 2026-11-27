package com.quorum.attendanceservice.service;

/** 400, a request that is well-formed but makes no sense (e.g. from after to). */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
