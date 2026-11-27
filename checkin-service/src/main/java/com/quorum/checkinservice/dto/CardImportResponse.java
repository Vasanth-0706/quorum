package com.quorum.checkinservice.dto;

/** imported = new cards, unchanged = rows already enrolled to the same student, total = distinct cards in the file. */
public record CardImportResponse(int imported, int unchanged, int total) {
}
