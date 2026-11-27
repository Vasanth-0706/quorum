package com.quorum.placementservice.config;

import com.quorum.placementservice.service.ForbiddenException;
import com.quorum.placementservice.service.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * TEMPORARY auth until proper login exists: every write request must carry
 * X-User-Id and X-User-Role (COORDINATOR or DIRECTOR). Reads are open.
 */
public class TemporaryAuthInterceptor implements HandlerInterceptor {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";
    static final Set<String> WRITE_ROLES = Set.of("COORDINATOR", "DIRECTOR");
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (READ_METHODS.contains(request.getMethod())) {
            return true;
        }
        String userId = request.getHeader(USER_ID_HEADER);
        String role = request.getHeader(USER_ROLE_HEADER);
        if (userId == null || userId.isBlank() || userId.length() > 64) {
            throw new UnauthorizedException("Missing or invalid " + USER_ID_HEADER + " header");
        }
        if (role == null || role.isBlank()) {
            throw new UnauthorizedException("Missing " + USER_ROLE_HEADER + " header");
        }
        if (!WRITE_ROLES.contains(role)) {
            throw new ForbiddenException(USER_ROLE_HEADER + " must be COORDINATOR or DIRECTOR");
        }
        return true;
    }
}
