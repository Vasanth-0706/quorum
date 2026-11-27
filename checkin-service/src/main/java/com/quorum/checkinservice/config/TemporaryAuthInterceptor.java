package com.quorum.checkinservice.config;

import com.quorum.checkinservice.service.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * TEMPORARY auth until proper login exists: every write request must carry
 * X-User-Id and X-User-Role (COORDINATOR or DIRECTOR). Reads are open.
 * POST /checkins/tap is excluded: readers authenticate with X-Device-Key instead.
 */
public class TemporaryAuthInterceptor implements HandlerInterceptor {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";
    public static final String DIRECTOR = "DIRECTOR";
    static final Set<String> WRITE_ROLES = Set.of("COORDINATOR", DIRECTOR);
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (READ_METHODS.contains(request.getMethod())) {
            return true;
        }
        String userId = request.getHeader(USER_ID_HEADER);
        String role = request.getHeader(USER_ROLE_HEADER);
        if (userId == null || userId.isBlank() || userId.length() > 64) {
            throw ApiException.unauthorized("Missing or invalid " + USER_ID_HEADER + " header");
        }
        if (role == null || role.isBlank()) {
            throw ApiException.unauthorized("Missing " + USER_ROLE_HEADER + " header");
        }
        if (!WRITE_ROLES.contains(role)) {
            throw ApiException.forbidden(USER_ROLE_HEADER + " must be COORDINATOR or DIRECTOR");
        }
        return true;
    }

    /** For DIRECTOR-only endpoints; the interceptor has already checked the headers. */
    public static void requireDirector(String role) {
        if (!DIRECTOR.equals(role)) {
            throw ApiException.forbidden("Only a DIRECTOR may do this");
        }
    }
}
