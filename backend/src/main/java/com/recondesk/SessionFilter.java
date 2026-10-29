package com.recondesk;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
class SessionFilter extends OncePerRequestFilter {
    static final String COOKIE = "recondesk_session";
    static final String SESSION = "recon.session";
    private final AuthService auth;
    private final String origin;
    SessionFilter(AuthService auth, @Value("${recon.origin}") String origin) { this.auth = auth; this.origin = origin; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        String path = request.getRequestURI();
        boolean mutation = !request.getMethod().equals("GET") && !request.getMethod().equals("HEAD");
        if (mutation && !origin.equals(request.getHeader("Origin"))) { error(response, 403, "ORIGIN", "Request origin is not allowed."); return; }
        if (path.equals("/api/health") && request.getMethod().equals("GET")) { chain.doFilter(request, response); return; }
        if (path.equals("/api/auth/login") && request.getMethod().equals("POST")) {
            String type = request.getContentType();
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).matches("application/json(?:\\s*;.*)?")) {
                error(response, 400, "VALIDATION", "Login requires application/json."); return;
            }
            chain.doFilter(request, response); return;
        }
        String token = null;
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) if (cookie.getName().equals(COOKIE)) token = cookie.getValue();
        AuthService.Session session;
        try { session = auth.authenticate(token); }
        catch (org.springframework.dao.DataAccessException unavailable) {
            error(response, 500, "INTERNAL_ERROR", "Session verification is unavailable. Try again shortly."); return;
        }
        if (session == null) { error(response, 401, "SESSION_REQUIRED", "Sign in to continue."); return; }
        if (mutation) {
            String supplied = request.getHeader("X-CSRF-Token");
            if (supplied == null || !MessageDigest.isEqual(session.csrfToken().getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                error(response, 403, "CSRF", "CSRF token is missing or invalid."); return;
            }
        }
        request.setAttribute(SESSION, session);
        chain.doFilter(request, response);
    }
    private void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
