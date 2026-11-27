package com.quorum.timetableservice.event;

/** The message can never be applied (missing or malformed field). Sent straight to the DLT. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
