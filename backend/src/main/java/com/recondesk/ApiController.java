package com.recondesk;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.recondesk.Model.*;

@RestController
@RequestMapping("/api")
class ApiController {
    private final WorkspaceService workspace;
    private final AuthService auth;
    private final JdbcTemplate jdbc;
    private final boolean cookieSecure;
    ApiController(WorkspaceService workspace, AuthService auth, JdbcTemplate jdbc, @Value("${recon.cookie-secure}") boolean cookieSecure) {
        this.workspace = workspace; this.auth = auth; this.jdbc = jdbc; this.cookieSecure = cookieSecure;
    }
    @GetMapping("/health")
    Map<String, String> health() { jdbc.queryForObject("SELECT 1", Integer.class); return Map.of("status", "ok"); }

    @PostMapping("/auth/login")
    ResponseEntity<?> login(@RequestBody Login input, HttpServletRequest request) {
        AuthService.LoginResult result = auth.login(input, request.getRemoteAddr());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(result.token(), Duration.ofHours(12)).toString())
                .body(new AuthResponse(result.session().user(), result.session().csrfToken()));
    }
    @GetMapping("/auth/session")
    AuthResponse session(HttpServletRequest request) {
        AuthService.Session session = current(request);
        return new AuthResponse(session.user(), session.csrfToken());
    }
    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(current(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }
    private ResponseCookie cookie(String value, Duration expiry) {
        return ResponseCookie.from(SessionFilter.COOKIE, value).httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/").maxAge(expiry).build();
    }

    @GetMapping("/runs")
    Page<Map<String, Object>> runs(@RequestParam(defaultValue = "0") int page) { return workspace.runs(page); }
    @PostMapping(value = "/runs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Map<String, Object>> create(@RequestParam String name, @RequestParam MultipartFile internalFile,
                                              @RequestParam MultipartFile partnerFile, @RequestHeader("Idempotency-Key") UUID key,
                                              HttpServletRequest request) throws IOException {
        WorkspaceService.requireRole(current(request).user(), "OPERATOR");
        WorkspaceService.ImportResult result = workspace.importRun(current(request).user(), name, key, internalFile.getBytes(), partnerFile.getBytes());
        return ResponseEntity.status(result.replay() ? 200 : 201).body(result.run());
    }
    @GetMapping("/runs/{id}")
    Map<String, Object> run(@PathVariable UUID id) { return workspace.run(id); }
    @GetMapping("/runs/{id}/cases")
    Page<Map<String, Object>> cases(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "ALL") String state, @RequestParam(defaultValue = "ALL") String classification,
                                   @RequestParam(defaultValue = "") String search) { return workspace.cases(id, page, state, classification, search); }
    @GetMapping("/cases/{id}")
    Map<String, Object> detail(@PathVariable UUID id) { return workspace.detail(id); }
    @PostMapping("/cases/{id}/proposals")
    Map<String, Object> propose(@PathVariable UUID id, @RequestBody ProposalInput input, HttpServletRequest request) {
        return workspace.propose(id, current(request).user(), input);
    }
    @PostMapping("/proposals/{id}/decision")
    Map<String, Object> decide(@PathVariable UUID id, @RequestBody DecisionInput input, HttpServletRequest request) {
        return workspace.decide(id, current(request).user(), input);
    }
    @GetMapping(value = "/runs/{id}/export.csv", produces = "text/csv;charset=UTF-8")
    ResponseEntity<String> export(@PathVariable UUID id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"recondesk-" + id + ".csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8)).body(workspace.export(id));
    }
    @GetMapping("/audit")
    Page<Map<String, Object>> audit(@RequestParam(defaultValue = "0") int page) { return workspace.audit(page); }
    private AuthService.Session current(HttpServletRequest request) { return (AuthService.Session) request.getAttribute(SessionFilter.SESSION); }
}
