package com.recondesk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.recondesk.Model.*;

@Service
class AuthService {
    record Session(User user, String csrfToken, String tokenHash) {
        @Override public String toString() { return "Session[user=" + user + ", csrfToken=[redacted], tokenHash=[redacted]]"; }
    }
    record LoginResult(Session session, String token) {
        @Override public String toString() { return "LoginResult[session=" + session + ", token=[redacted]]"; }
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash = passwords.encode(UUID.randomUUID().toString());

    AuthService(JdbcTemplate jdbc, TransactionTemplate transactions) { this.jdbc = jdbc; this.transactions = transactions; }

    @Bean
    CommandLineRunner seedAccounts(@Value("${recon.demo-password}") String password) {
        return args -> transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM workspace_guard WHERE id=1 FOR UPDATE", Integer.class);
            if (jdbc.queryForObject("SELECT count(*) FROM app_user", Long.class) == 0) {
                if (password.getBytes(StandardCharsets.UTF_8).length < 16 || password.getBytes(StandardCharsets.UTF_8).length > 72)
                    throw new IllegalStateException("Set RECON_DEMO_PASSWORD to a generated secret of 16 to 72 UTF-8 bytes before first startup.");
                String hash = passwords.encode(password);
                jdbc.update("INSERT INTO app_user(id,name,email,role,password_hash) VALUES (?,?,?,?,?),(?,?,?,?,?)",
                        UUID.randomUUID(), "Demo Operator", "operator@recondesk.test", "OPERATOR", hash,
                        UUID.randomUUID(), "Demo Reviewer", "reviewer@recondesk.test", "REVIEWER", hash);
            }
        });
    }

    LoginResult login(Login input, String remoteAddress) {
        if (input == null || input.email() == null || input.password() == null || input.email().indexOf('\0') >= 0 || input.email().length() > 254
                || input.password().isEmpty() || input.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(401, "LOGIN_FAILED", "Email or password is incorrect.");
        String email = input.email().strip().toLowerCase(Locale.ROOT);
        String key = sha256(remoteAddress.getBytes(StandardCharsets.UTF_8));
        Object outcome = transactions.execute(status -> {
            Instant now = Instant.now();
            jdbc.update("INSERT INTO login_throttle(key_hash,window_start) VALUES (?,?) ON CONFLICT DO NOTHING", key, java.sql.Timestamp.from(now));
            Map<String, Object> throttle = jdbc.queryForMap("SELECT * FROM login_throttle WHERE key_hash=? FOR UPDATE", key);
            java.sql.Timestamp blocked = (java.sql.Timestamp) throttle.get("blocked_until");
            if (blocked != null && blocked.toInstant().isAfter(now))
                return new ApiException(429, "LOGIN_THROTTLED", "Too many login attempts. Try again in 15 minutes.");
            List<Map<String, Object>> accounts = jdbc.queryForList("SELECT * FROM app_user WHERE email=?", email);
            String hash = accounts.isEmpty() ? dummyHash : (String) accounts.get(0).get("password_hash");
            if (!passwords.matches(input.password(), hash) || accounts.isEmpty()) {
                java.sql.Timestamp window = (java.sql.Timestamp) throttle.get("window_start");
                int failures = window.toInstant().plusSeconds(900).isBefore(now) ? 1 : ((Number) throttle.get("failures")).intValue() + 1;
                Instant windowStart = failures == 1 ? now : window.toInstant();
                jdbc.update("UPDATE login_throttle SET failures=?,window_start=?,blocked_until=? WHERE key_hash=?", failures,
                        java.sql.Timestamp.from(windowStart), failures >= 5 ? java.sql.Timestamp.from(now.plusSeconds(900)) : null, key);
                return new ApiException(401, "LOGIN_FAILED", "Email or password is incorrect.");
            }
            Map<String, Object> account = accounts.get(0);
            User user = new User((UUID) account.get("id"), (String) account.get("name"), email, (String) account.get("role"));
            String token = token(), csrf = token(), tokenHash = sha256(token.getBytes(StandardCharsets.UTF_8));
            jdbc.update("DELETE FROM app_session WHERE expires_at<=?", java.sql.Timestamp.from(now));
            jdbc.update("INSERT INTO app_session(token_hash,user_id,csrf_token,expires_at) VALUES (?,?,?,?)", tokenHash,
                    user.id(), csrf, java.sql.Timestamp.from(now.plusSeconds(43_200)));
            jdbc.update("DELETE FROM login_throttle WHERE key_hash=?", key);
            return new LoginResult(new Session(user, csrf, tokenHash), token);
        });
        if (outcome instanceof ApiException error) throw error;
        return (LoginResult) outcome;
    }

    Session authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        String hash = sha256(token.getBytes(StandardCharsets.UTF_8));
        List<Session> sessions = jdbc.query("SELECT u.id,u.name,u.email,u.role,s.csrf_token FROM app_session s JOIN app_user u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>now()",
                (rs, row) -> new Session(user(rs, ""), rs.getString("csrf_token"), hash), hash);
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    void logout(Session session) { jdbc.update("DELETE FROM app_session WHERE token_hash=?", session.tokenHash()); }
    static User user(ResultSet rs, String prefix) throws SQLException {
        return new User(rs.getObject(prefix + "id", UUID.class), rs.getString(prefix + "name"), rs.getString(prefix + "email"), rs.getString(prefix + "role"));
    }
    private String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String payloadHash(String name, byte[] internal, byte[] partner) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : List.of(name.getBytes(StandardCharsets.UTF_8), internal, partner)) {
                digest.update(ByteBuffer.allocate(4).putInt(part.length).array());
                digest.update(part);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
