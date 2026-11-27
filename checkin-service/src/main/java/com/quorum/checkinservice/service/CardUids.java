package com.quorum.checkinservice.service;

import java.util.Locale;
import java.util.regex.Pattern;

/** Card UID format: upper-case hex, no separators, 4, 7 or 10 bytes (what an RC522 reads). */
public final class CardUids {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s:\\-]");
    private static final Pattern HEX = Pattern.compile("[0-9A-F]+");

    private CardUids() {
    }

    /** {@code "04:a3-1b 2c"} → {@code "04A31B2C"}. 400 INVALID_CARD_UID if it is not a 4/7/10-byte hex UID. */
    public static String normalize(String raw) {
        if (raw == null) {
            throw invalid(null);
        }
        String uid = SEPARATORS.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
        int len = uid.length();
        if (!HEX.matcher(uid).matches() || (len != 8 && len != 14 && len != 20)) {
            throw invalid(raw);
        }
        return uid;
    }

    private static ApiException invalid(String raw) {
        return ApiException.badRequest("INVALID_CARD_UID",
                "cardUid must be 4, 7 or 10 bytes of hex (8, 14 or 20 hex digits): " + raw);
    }
}
