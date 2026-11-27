package com.quorum.placementservice.service;

/** 404, the requested drive, round or session does not exist. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
