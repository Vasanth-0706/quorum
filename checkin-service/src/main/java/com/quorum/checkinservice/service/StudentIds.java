package com.quorum.checkinservice.service;

import java.util.regex.Pattern;

public final class StudentIds {

    public static final String REGEX = "\\d{10}";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private StudentIds() {
    }

    public static boolean valid(String s) {
        return s != null && PATTERN.matcher(s).matches();
    }
}
