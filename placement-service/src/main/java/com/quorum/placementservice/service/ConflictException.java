package com.quorum.placementservice.service;

/** 409, the request clashes with the current state. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
