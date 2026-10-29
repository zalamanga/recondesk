package com.recondesk;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.recondesk.Model.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RECON_TEST_DATABASE_URL", matches = ".+")
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresIntegrationTest {
    private static final String ORIGIN = "http://127.0.0.1:8096";
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String PASSWORD_HASH = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode(PASSWORD);
    private static final String HEADER = "reference,date,amount,currency\n";
    private static final String INTERNAL = HEADER + "MATCH,2026-10-01,10,USD\nCHANGE,2026-10-01,20,USD\nDUP,2026-10-01,30,USD\nDUP,2026-10-01,31,USD\nONLY,2026-10-01,40,USD\n";
    private static final String PARTNER = HEADER + "MATCH,2026-10-01,10,USD\nCHANGE,2026-10-02,22,IDR\nDUP,2026-10-01,30,USD\nREMOTE,2026-10-01,50,USD\n";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkspaceService workspace;
    @Autowired AuthService auth;
    private final HttpClient client = HttpClient.newHttpClient();
    record WebSession(String cookie, String csrf) { }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("RECON_TEST_DATABASE_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/[^?]*test[^?]*(\\?.*)?"))
            throw new IllegalArgumentException("Integration tests require a dedicated PostgreSQL database whose name contains test.");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> System.getenv("RECON_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("RECON_TEST_DB_PASSWORD"));
        properties.add("recon.demo-password", () -> PASSWORD);
        properties.add("recon.origin", () -> ORIGIN);
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @BeforeEach
    void resetSyntheticWorkspace() {
        jdbc.execute("TRUNCATE recon_run CASCADE");
        jdbc.execute("TRUNCATE app_session,login_throttle");
        jdbc.update("UPDATE app_user SET password_hash=? WHERE email IN ('operator@recondesk.test','reviewer@recondesk.test')", PASSWORD_HASH);
    }

    @Test
    void sessionOriginCsrfExpiryAndRevocationAreEnforced() throws Exception {
        assertEquals(200, request("GET", "/api/health", null, null, null, null).statusCode());
        assertEquals(401, request("GET", "/api/runs", null, null, null, null).statusCode());
        for (String path : List.of("/api;/runs", "/api;segment/runs", "/api%3b/runs", "/api%3B/runs", "/api%253b/runs"))
            assertNotEquals(200, request("GET", path, null, null, null, null).statusCode(), path);
        assertEquals(403, json("/api/auth/login", "{\"email\":\"operator@recondesk.test\",\"password\":\"" + PASSWORD + "\"}", null, "https://untrusted.invalid").statusCode());
        var response = loginResponse("operator");
        String cookieHeader = response.headers().firstValue("set-cookie").orElseThrow();
        assertTrue(cookieHeader.contains("HttpOnly"));
        assertTrue(cookieHeader.contains("SameSite=Lax"));
        assertTrue(cookieHeader.contains("Path=/"));
        assertTrue(cookieHeader.contains("Max-Age=43200"));
        WebSession session = session(response);
        String rawToken = session.cookie().split("=", 2)[1];
        String storedHash = jdbc.queryForObject("SELECT token_hash FROM app_session", String.class);
        assertEquals(AuthService.sha256(rawToken.getBytes(StandardCharsets.UTF_8)), storedHash);
        assertNotEquals(rawToken, storedHash);
        assertEquals(200, request("GET", "/api/auth/session", null, null, session, null).statusCode());
        assertEquals(403, request("POST", "/api/auth/logout", "", "application/json", session, ORIGIN).statusCode());
        assertEquals(403, json("/api/auth/logout", "{}", session, "https://untrusted.invalid").statusCode());
        assertEquals(204, json("/api/auth/logout", "{}", session, ORIGIN).statusCode());
        assertEquals(401, request("GET", "/api/runs", null, null, session, null).statusCode());
        WebSession expired = session(loginResponse("operator"));
        jdbc.update("UPDATE app_session SET expires_at=now()-interval '1 second'");
        assertEquals(401, request("GET", "/api/runs", null, null, expired, null).statusCode());
    }

    @Test
    void importsAreAtomicIdempotentAndExposeCompleteSourceEvidence() throws Exception {
        WebSession operator = session(loginResponse("operator"));
        UUID key = UUID.randomUUID();
        var created = upload(operator, key, "October comparison", INTERNAL, PARTNER);
        assertEquals(201, created.statusCode(), created.body());
        UUID runId = UUID.fromString(JsonPath.read(created.body(), "$.id"));
        assertEquals(5, ((Number) JsonPath.read(created.body(), "$.caseCount")).intValue());
        assertEquals(1, ((Number) JsonPath.read(created.body(), "$.matchedCount")).intValue());
        assertEquals(4, ((Number) JsonPath.read(created.body(), "$.openCount")).intValue());
        assertEquals(200, upload(operator, key, "October comparison", INTERNAL, PARTNER).statusCode());
        assertEquals(409, upload(operator, key, "Changed comparison", INTERNAL, PARTNER).statusCode());
        assertEquals(400, upload(operator, UUID.randomUUID(), "Invalid partner", INTERNAL, HEADER + "BROKEN,2026-10-01,-1,USD\n").statusCode());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM recon_run", Long.class));
        assertEquals(9L, jdbc.queryForObject("SELECT count(*) FROM source_row", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class));
        var duplicate = workspace.cases(runId, 0, "ALL", "DUPLICATE", "").items().get(0);
        var detail = workspace.detail((UUID) duplicate.get("id"));
        assertEquals(2, ((List<?>) detail.get("internalRows")).size());
        assertNull(detail.get("internalAmount"));
        assertNull(detail.get("partnerAmount"));
        assertNull(detail.get("delta"));
        var mismatch = workspace.cases(runId, 0, "OPEN", "DIFFERENT", "CHANGE").items().get(0);
        assertEquals(List.of("AMOUNT", "DATE", "CURRENCY"), mismatch.get("differences"));
        assertNull(mismatch.get("delta"));
        assertEquals(0, workspace.cases(runId, 0, "ALL", "ALL", "%' OR 1=1 --").total());
        assertEquals(0, workspace.cases(runId, 0, "ALL", "ALL", "%").total());
    }

    @Test
    void parallelImportRetriesCommitExactlyOneRun() throws Exception {
        User operator = user("OPERATOR");
        UUID key = UUID.randomUUID();
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<WorkspaceService.ImportResult> action = () -> { start.await(10, TimeUnit.SECONDS); return importRun(operator, key); };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(action); var second = pool.submit(action);
            var a = first.get(30, TimeUnit.SECONDS); var b = second.get(30, TimeUnit.SECONDS);
            assertEquals(a.run().get("id"), b.run().get("id"));
            assertNotEquals(a.replay(), b.replay());
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM recon_run", Long.class));
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void workflowChecksRolesVersionsDifferentActorAndRejectionHistory() throws Exception {
        User operator = user("OPERATOR"), reviewer = user("REVIEWER");
        UUID runId = (UUID) importRun(operator, UUID.randomUUID()).run().get("id");
        UUID caseId = (UUID) workspace.cases(runId, 0, "OPEN", "DIFFERENT", "").items().get(0).get("id");
        UUID matchedCase = (UUID) workspace.cases(runId, 0, "MATCHED", "MATCHED", "").items().get(0).get("id");
        assertStatus(409, () -> workspace.propose(matchedCase, operator, new ProposalInput(0L, "A matched case cannot be proposed.")));
        WebSession operatorSession = session(loginResponse("operator")), reviewerSession = session(loginResponse("reviewer"));
        assertEquals(403, upload(reviewerSession, UUID.randomUUID(), "Forbidden import", INTERNAL, PARTNER).statusCode());
        assertEquals(403, json("/api/cases/" + caseId + "/proposals", "{\"expectedVersion\":0,\"note\":\"A reviewer cannot propose\"}", reviewerSession, ORIGIN).statusCode());
        assertEquals(400, json("/api/cases/" + caseId + "/proposals", "{\"note\":\"A version is required\"}", operatorSession, ORIGIN).statusCode());
        assertEquals(400, json("/api/cases/" + caseId + "/proposals", "{\"expectedVersion\":0.5,\"note\":\"No fractional versions\"}", operatorSession, ORIGIN).statusCode());
        assertEquals(400, json("/api/cases/" + caseId + "/proposals", "{\"expectedVersion\":\"0\",\"note\":\"No textual versions\"}", operatorSession, ORIGIN).statusCode());
        var pending = workspace.propose(caseId, operator, new ProposalInput(0L, "Source dates require partner confirmation."));
        UUID proposal = proposalId(pending);
        assertEquals("PENDING", pending.get("state"));
        assertEquals(1L, pending.get("version"));
        assertStatus(409, () -> workspace.propose(caseId, operator, new ProposalInput(0L, "Stale proposal must be rejected.")));
        assertStatus(403, () -> workspace.decide(proposal, operator, new DecisionInput(1L, "APPROVE", "Operator cannot approve own work.")));
        User samePersonReviewer = new User(operator.id(), operator.name(), operator.email(), "REVIEWER");
        assertStatus(403, () -> workspace.decide(proposal, samePersonReviewer, new DecisionInput(1L, "APPROVE", "Same actor remains prohibited.")));
        var rejected = workspace.decide(proposal, reviewer, new DecisionInput(1L, "REJECT", "Please include the partner confirmation."));
        assertEquals("OPEN", rejected.get("state"));
        assertEquals(2L, rejected.get("version"));
        var resubmitted = workspace.propose(caseId, operator, new ProposalInput(2L, "Partner confirmed the source date difference."));
        var resolved = workspace.decide(proposalId(resubmitted), reviewer, new DecisionInput(3L, "APPROVE", "Partner evidence supports this explanation."));
        assertEquals("RESOLVED", resolved.get("state"));
        assertEquals(4L, resolved.get("version"));
        assertEquals(2, ((List<?>) resolved.get("proposals")).size());
        assertStatus(409, () -> workspace.decide(proposalId(resubmitted), reviewer, new DecisionInput(3L, "APPROVE", "Repeated approval cannot append audit.")));
        assertEquals(4L, jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE case_id=?", Long.class, caseId));
        assertEquals(9L, jdbc.queryForObject("SELECT count(*) FROM source_row", Long.class));
        assertEquals("20.00", jdbc.queryForObject("SELECT amount::text FROM source_row WHERE case_id=? AND source='INTERNAL'", String.class, caseId));
        assertEquals(1, ((Number) workspace.run(runId).get("resolvedCount")).intValue());
    }

    @Test
    void racingDecisionsHaveOneWinnerAndOneAuditEvent() throws Exception {
        User operator = user("OPERATOR"), reviewer = user("REVIEWER");
        UUID runId = (UUID) importRun(operator, UUID.randomUUID()).run().get("id");
        UUID caseId = (UUID) workspace.cases(runId, 0, "OPEN", "DIFFERENT", "").items().get(0).get("id");
        UUID proposal = proposalId(workspace.propose(caseId, operator, new ProposalInput(0L, "Ready for an independent review.")));
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Integer> decision = () -> {
            start.await(10, TimeUnit.SECONDS);
            try { workspace.decide(proposal, reviewer, new DecisionInput(1L, "APPROVE", "Evidence is sufficient for approval.")); return 200; }
            catch (ApiException error) { return error.status; }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(decision); var b = pool.submit(decision);
            var results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertTrue(results.contains(200)); assertTrue(results.contains(409));
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='PROPOSAL_APPROVED'", Long.class));
            assertEquals(2L, workspace.detail(caseId).get("version"));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void exportIncludesEveryCaseAndProtectsSpreadsheetFormulas() throws Exception {
        User operator = user("OPERATOR");
        StringBuilder source = new StringBuilder(HEADER);
        for (int i = 0; i < 1000; i++) source.append(i == 0 ? "-FORMULA" : "ROW/" + i).append(",2026-10-01,1,USD\n");
        UUID runId = (UUID) workspace.importRun(operator, "Full export", UUID.randomUUID(), bytes(source.toString()), bytes(HEADER + "PARTNER,2026-10-01,2,USD\n")).run().get("id");
        String exported = workspace.export(runId);
        assertEquals(1002, exported.lines().count());
        assertTrue(exported.contains("'-FORMULA,INTERNAL_ONLY"));
        assertTrue(exported.contains("'-2.00"));
        assertEquals(1001, workspace.cases(runId, 0, "ALL", "ALL", "").total());
        assertEquals(25, workspace.cases(runId, 0, "ALL", "ALL", "").items().size());
        assertEquals(1, workspace.cases(runId, 40, "ALL", "ALL", "").items().size());
        var response = request("GET", "/api/runs/" + runId + "/export.csv", null, null, session(loginResponse("operator")), null);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-disposition").orElseThrow().contains("attachment"));
    }

    @Test
    void repeatedWrongPasswordsAreThrottledWithoutRevealingAccountExistence() throws Exception {
        for (int i = 0; i < 5; i++) assertEquals(401, json("/api/auth/login", "{\"email\":\"missing@recondesk.test\",\"password\":\"incorrect-password\"}", null, ORIGIN).statusCode());
        assertEquals(429, loginResponse("operator").statusCode());
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM app_session", Long.class));
    }

    @Test
    void authenticationSecretsAreAbsentFromApplicationLogs(CapturedOutput output) throws Exception {
        WebSession session = session(loginResponse("operator"));
        request("GET", "/api/auth/session", null, null, session, null);
        assertFalse(output.getAll().contains(PASSWORD));
        assertFalse(output.getAll().contains(session.csrf()));
        assertFalse(output.getAll().contains(session.cookie().split("=", 2)[1]));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jdbcDateMappingPreservesHistoricalSourceDates() {
        String source = HEADER + "EARLY,0001-01-01,1,USD\nCUTOVER,1582-10-10,1,USD\nFUTURE,9999-12-31,1,USD\n";
        UUID runId = (UUID) workspace.importRun(user("OPERATOR"), "Date boundaries", UUID.randomUUID(), bytes(source), bytes(source)).run().get("id");
        for (var summary : workspace.cases(runId, 0, "ALL", "ALL", "").items()) {
            var detail = workspace.detail((UUID) summary.get("id"));
            SourceRow row = ((List<SourceRow>) detail.get("internalRows")).get(0);
            String expected = switch (row.reference()) { case "EARLY" -> "0001-01-01"; case "CUTOVER" -> "1582-10-10"; default -> "9999-12-31"; };
            assertEquals(expected, row.date().toString());
            assertEquals("MATCHED", detail.get("state"));
        }
    }

    @Test
    void characterBoundsAgreeWithPostgresqlForSupplementaryUnicode() {
        User operator = user("OPERATOR"), reviewer = user("REVIEWER");
        String emoji = "\uD83D\uDD0E";
        UUID runId = (UUID) workspace.importRun(operator, emoji.repeat(80), UUID.randomUUID(), bytes(INTERNAL), bytes(PARTNER)).run().get("id");
        assertStatus(400, () -> workspace.importRun(operator, emoji.repeat(81), UUID.randomUUID(), bytes(INTERNAL), bytes(PARTNER)));
        UUID caseId = (UUID) workspace.cases(runId, 0, "OPEN", "DIFFERENT", "").items().get(0).get("id");
        assertStatus(400, () -> workspace.propose(caseId, operator, new ProposalInput(0L, emoji.repeat(5))));
        UUID proposalId = proposalId(workspace.propose(caseId, operator, new ProposalInput(0L, emoji.repeat(10))));
        assertStatus(400, () -> workspace.decide(proposalId, reviewer, new DecisionInput(1L, "APPROVE", emoji.repeat(5))));
        assertEquals("RESOLVED", workspace.decide(proposalId, reviewer, new DecisionInput(1L, "APPROVE", emoji.repeat(10))).get("state"));
    }

    private User user(String role) {
        return jdbc.queryForObject("SELECT id,name,email,role FROM app_user WHERE role=?", (rs, row) -> AuthService.user(rs, ""), role);
    }
    private WorkspaceService.ImportResult importRun(User actor, UUID key) { return workspace.importRun(actor, "October comparison", key, bytes(INTERNAL), bytes(PARTNER)); }
    @SuppressWarnings("unchecked")
    private UUID proposalId(Map<String, Object> detail) { return (UUID) ((Map<String, Object>) ((List<?>) detail.get("proposals")).get(0)).get("id"); }
    private void assertStatus(int status, org.junit.jupiter.api.function.Executable action) { assertEquals(status, assertThrows(ApiException.class, action).status); }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private HttpResponse<String> loginResponse(String role) throws Exception { return json("/api/auth/login", "{\"email\":\"" + role + "@recondesk.test\",\"password\":\"" + PASSWORD + "\"}", null, ORIGIN); }
    private WebSession session(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return new WebSession(response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0], JsonPath.read(response.body(), "$.csrfToken"));
    }
    private HttpResponse<String> json(String path, String body, WebSession session, String origin) throws Exception {
        return send("POST", path, body, "application/json", session, origin, true, null);
    }
    private HttpResponse<String> request(String method, String path, String body, String type, WebSession session, String origin) throws Exception {
        return send(method, path, body, type, session, origin, false, null);
    }
    private HttpResponse<String> upload(WebSession session, UUID key, String name, String internal, String partner) throws Exception {
        String boundary = "recon" + UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n" + name
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"internalFile\"; filename=\"internal.csv\"\r\nContent-Type: text/csv\r\n\r\n" + internal
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"partnerFile\"; filename=\"partner.csv\"\r\nContent-Type: text/csv\r\n\r\n" + partner + "\r\n--" + boundary + "--\r\n";
        return send("POST", "/api/runs", body, "multipart/form-data; boundary=" + boundary, session, ORIGIN, true, key);
    }
    private HttpResponse<String> send(String method, String path, String body, String type, WebSession session, String origin, boolean csrf, UUID key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(java.time.Duration.ofSeconds(30));
        if (session != null) { builder.header("Cookie", session.cookie()); if (csrf) builder.header("X-CSRF-Token", session.csrf()); }
        if (origin != null) builder.header("Origin", origin);
        if (type != null) builder.header("Content-Type", type);
        if (key != null) builder.header("Idempotency-Key", key.toString());
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
