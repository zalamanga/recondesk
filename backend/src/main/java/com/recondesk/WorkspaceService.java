package com.recondesk;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.recondesk.Model.*;

@Service
class WorkspaceService {
    record ImportResult(Map<String, Object> run, boolean replay) { }
    private static final String ACTOR = "u.id actor_id,u.name actor_name,u.email actor_email,u.role actor_role";
    private static final String RUN_SELECT = "SELECT r.*, " + ACTOR + ", count(c.id) case_count, "
            + "count(c.id) FILTER (WHERE c.state='MATCHED') matched_count,count(c.id) FILTER (WHERE c.state='OPEN') open_count,"
            + "count(c.id) FILTER (WHERE c.state='PENDING') pending_count,count(c.id) FILTER (WHERE c.state='RESOLVED') resolved_count "
            + "FROM recon_run r JOIN app_user u ON u.id=r.created_by LEFT JOIN recon_case c ON c.run_id=r.id ";
    private static final String AUDIT_SELECT = "SELECT a.*, " + ACTOR + " FROM audit_event a JOIN app_user u ON u.id=a.actor_id ";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final CsvSources csv;

    WorkspaceService(JdbcTemplate jdbc, TransactionTemplate transactions, CsvSources csv) {
        this.jdbc = jdbc; this.transactions = transactions; this.csv = csv;
    }

    ImportResult importRun(User actor, String name, UUID key, byte[] internal, byte[] partner) {
        requireRole(actor, "OPERATOR");
        if (name == null || name.indexOf('\0') >= 0 || characters(name.strip()) < 3 || characters(name.strip()) > 80) throw ApiException.invalid("Run name must contain 3 to 80 characters.");
        if (key == null) throw ApiException.invalid("Idempotency-Key must be a UUID.");
        if (internal.length > CsvSources.MAX_BYTES || partner.length > CsvSources.MAX_BYTES)
            throw new ApiException(413, "UPLOAD_LIMIT", "Each source must be at most 1 MiB.");
        String hash = AuthService.payloadHash(name, internal, partner);
        return transactions.execute(status -> {
            // The singleton guard also serializes the workspace cap and concurrent first-use import keys.
            jdbc.queryForObject("SELECT id FROM workspace_guard WHERE id=1 FOR UPDATE", Integer.class);
            List<Map<String, Object>> previous = jdbc.queryForList("SELECT payload_hash,run_id FROM import_key WHERE actor_id=? AND idempotency_key=?", actor.id(), key);
            if (!previous.isEmpty()) {
                if (!hash.equals(previous.get(0).get("payload_hash"))) throw ApiException.conflict("This idempotency key belongs to different input. Choose a new key.");
                return new ImportResult(run((UUID) previous.get(0).get("run_id")), true);
            }
            List<SourceRow> a = csv.parse(internal, "Internal source"), b = csv.parse(partner, "Partner source");
            List<Comparison> comparisons = csv.compare(a, b);
            if (jdbc.queryForObject("SELECT count(*) FROM recon_run", Long.class) >= 200) throw ApiException.conflict("This workspace has reached its 200-run limit.");
            UUID runId = UUID.randomUUID();
            jdbc.update("INSERT INTO recon_run(id,name,created_at,created_by,internal_count,partner_count) VALUES (?,?,?,?,?,?)", runId, name.strip(), now(), actor.id(), a.size(), b.size());
            List<Object[]> cases = new ArrayList<>(), rows = new ArrayList<>();
            for (Comparison comparison : comparisons) {
                UUID caseId = UUID.randomUUID();
                cases.add(new Object[]{caseId, runId, comparison.reference(), comparison.classification(), String.join(",", comparison.differences()),
                        comparison.classification().equals("MATCHED") ? "MATCHED" : "OPEN", comparison.internalRows().size(), comparison.partnerRows().size(),
                        money(comparison.internalAmount()), money(comparison.partnerAmount()), money(comparison.delta())});
                addRows(rows, caseId, "INTERNAL", comparison.internalRows());
                addRows(rows, caseId, "PARTNER", comparison.partnerRows());
            }
            jdbc.batchUpdate("INSERT INTO recon_case(id,run_id,reference,classification,differences,state,internal_count,partner_count,internal_amount,partner_amount,delta) VALUES (?,?,?,?,?,?,?,?,?,?,?)", cases);
            jdbc.batchUpdate("INSERT INTO source_row(id,case_id,source,record_line,reference,transaction_date,amount,currency) VALUES (?,?,?,?,?,?,?,?)", rows);
            jdbc.update("INSERT INTO import_key(actor_id,idempotency_key,payload_hash,run_id) VALUES (?,?,?,?)", actor.id(), key, hash, runId);
            audit(runId, null, actor, "RUN_IMPORTED", "Imported " + a.size() + " internal and " + b.size() + " partner rows.");
            return new ImportResult(run(runId), false);
        });
    }

    private void addRows(List<Object[]> destination, UUID caseId, String source, List<SourceRow> rows) {
        for (SourceRow row : rows) destination.add(new Object[]{UUID.randomUUID(), caseId, source, row.line(), row.reference(), row.date(), row.money(), row.currency()});
    }
    private BigDecimal money(String value) { return value == null ? null : new BigDecimal(value); }

    Page<Map<String, Object>> runs(int page) {
        int offset = offset(page);
        List<Map<String, Object>> items = jdbc.query(RUN_SELECT + "GROUP BY r.id,u.id ORDER BY r.created_at DESC,r.id DESC LIMIT 25 OFFSET ?", this::runMap, offset);
        return new Page<>(items, jdbc.queryForObject("SELECT count(*) FROM recon_run", Long.class), page, 25);
    }
    Map<String, Object> run(UUID id) {
        List<Map<String, Object>> found = jdbc.query(RUN_SELECT + "WHERE r.id=? GROUP BY r.id,u.id", this::runMap, id);
        if (found.isEmpty()) throw ApiException.missing();
        return found.get(0);
    }
    private Map<String, Object> runMap(ResultSet rs, int index) throws SQLException {
        return map("id", rs.getObject("id"), "name", rs.getString("name"), "createdAt", timestamp(rs, "created_at"), "createdBy", AuthService.user(rs, "actor_"),
                "internalCount", rs.getInt("internal_count"), "partnerCount", rs.getInt("partner_count"), "caseCount", rs.getInt("case_count"),
                "matchedCount", rs.getInt("matched_count"), "openCount", rs.getInt("open_count"), "pendingCount", rs.getInt("pending_count"), "resolvedCount", rs.getInt("resolved_count"));
    }

    Page<Map<String, Object>> cases(UUID runId, int page, String state, String classification, String search) {
        int offset = offset(page);
        run(runId);
        if (!Set.of("ALL", "MATCHED", "OPEN", "PENDING", "RESOLVED").contains(state)) throw ApiException.invalid("Unknown case state.");
        if (!Set.of("ALL", "MATCHED", "DIFFERENT", "INTERNAL_ONLY", "PARTNER_ONLY", "DUPLICATE").contains(classification)) throw ApiException.invalid("Unknown classification.");
        if (search.indexOf('\0') >= 0 || search.length() > 64) throw ApiException.invalid("Search must contain at most 64 characters.");
        String where = " WHERE run_id=?";
        List<Object> parameters = new ArrayList<>(List.of(runId));
        if (!state.equals("ALL")) { where += " AND state=?"; parameters.add(state); }
        if (!classification.equals("ALL")) { where += " AND classification=?"; parameters.add(classification); }
        if (!search.isEmpty()) {
            where += " AND reference ILIKE ? ESCAPE '!'";
            parameters.add("%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM recon_case" + where, Long.class, parameters.toArray());
        parameters.add(offset);
        return new Page<>(jdbc.query("SELECT * FROM recon_case" + where + " ORDER BY reference,id LIMIT 25 OFFSET ?", this::caseMap, parameters.toArray()), total, page, 25);
    }
    private Map<String, Object> caseMap(ResultSet rs, int index) throws SQLException {
        String differences = rs.getString("differences");
        return map("id", rs.getObject("id"), "runId", rs.getObject("run_id"), "reference", rs.getString("reference"), "classification", rs.getString("classification"),
                "differences", differences.isEmpty() ? List.of() : Arrays.asList(differences.split(",")), "state", rs.getString("state"), "version", rs.getLong("version"),
                "internalCount", rs.getInt("internal_count"), "partnerCount", rs.getInt("partner_count"), "internalAmount", decimal(rs, "internal_amount"),
                "partnerAmount", decimal(rs, "partner_amount"), "delta", decimal(rs, "delta"));
    }

    Map<String, Object> detail(UUID id) {
        List<Map<String, Object>> found = jdbc.query("SELECT c.*,r.name run_name FROM recon_case c JOIN recon_run r ON r.id=c.run_id WHERE c.id=?",
                (rs, index) -> { Map<String, Object> result = caseMap(rs, index); result.put("runName", rs.getString("run_name")); return result; }, id);
        if (found.isEmpty()) throw ApiException.missing();
        Map<String, Object> result = found.get(0);
        for (String source : List.of("INTERNAL", "PARTNER")) {
            List<SourceRow> rows = jdbc.query("SELECT * FROM source_row WHERE case_id=? AND source=? ORDER BY record_line,id",
                    (rs, index) -> new SourceRow(rs.getInt("record_line"), rs.getString("reference"), rs.getObject("transaction_date", java.time.LocalDate.class), decimal(rs, "amount"), rs.getString("currency")), id, source);
            result.put(source.equals("INTERNAL") ? "internalRows" : "partnerRows", rows);
        }
        result.put("proposals", jdbc.query("SELECT p.*, " + ACTOR + ", d.id decider_id,d.name decider_name,d.email decider_email,d.role decider_role FROM proposal p JOIN app_user u ON u.id=p.created_by LEFT JOIN app_user d ON d.id=p.decided_by WHERE p.case_id=? ORDER BY p.created_at DESC,p.id DESC",
                (rs, index) -> map("id", rs.getObject("id"), "caseId", rs.getObject("case_id"), "note", rs.getString("note"), "createdBy", AuthService.user(rs, "actor_"),
                        "createdAt", timestamp(rs, "created_at"), "status", rs.getString("status"), "decisionNote", rs.getString("decision_note"),
                        "decidedBy", rs.getObject("decided_by") == null ? null : AuthService.user(rs, "decider_"), "decidedAt", timestamp(rs, "decided_at")), id));
        result.put("audit", jdbc.query(AUDIT_SELECT + "WHERE a.case_id=? ORDER BY a.created_at DESC,a.id DESC", this::auditMap, id));
        return result;
    }

    Map<String, Object> propose(UUID caseId, User actor, ProposalInput input) {
        requireRole(actor, "OPERATOR");
        validateMutation(input == null ? null : input.expectedVersion(), input == null ? null : input.note());
        return transactions.execute(status -> {
            Map<String, Object> row = lockedCase(caseId);
            checkVersion(row, input.expectedVersion());
            if (!row.get("state").equals("OPEN")) throw ApiException.conflict("Only an open exception can receive a proposal.");
            jdbc.update("INSERT INTO proposal(id,case_id,note,created_by,created_at,status) VALUES (?,?,?,?,?,'PENDING')", UUID.randomUUID(), caseId, input.note().strip(), actor.id(), now());
            jdbc.update("UPDATE recon_case SET state='PENDING',version=version+1 WHERE id=?", caseId);
            audit((UUID) row.get("run_id"), caseId, actor, "PROPOSAL_SUBMITTED", input.note().strip());
            return detail(caseId);
        });
    }

    Map<String, Object> decide(UUID proposalId, User actor, DecisionInput input) {
        requireRole(actor, "REVIEWER");
        validateMutation(input == null ? null : input.expectedVersion(), input == null ? null : input.note());
        if (!Set.of("APPROVE", "REJECT").contains(input.decision() == null ? "" : input.decision())) throw ApiException.invalid("Decision must be APPROVE or REJECT.");
        return transactions.execute(status -> {
            List<UUID> ids = jdbc.query("SELECT case_id FROM proposal WHERE id=?", (rs, index) -> rs.getObject(1, UUID.class), proposalId);
            if (ids.isEmpty()) throw ApiException.missing();
            UUID caseId = ids.get(0);
            Map<String, Object> row = lockedCase(caseId);
            checkVersion(row, input.expectedVersion());
            Map<String, Object> proposal = jdbc.queryForMap("SELECT * FROM proposal WHERE id=?", proposalId);
            if (!row.get("state").equals("PENDING") || !proposal.get("status").equals("PENDING")) throw ApiException.conflict("This proposal has already been decided or is no longer pending.");
            if (proposal.get("created_by").equals(actor.id())) throw ApiException.forbidden("A different person must review this proposal.");
            boolean approve = input.decision().equals("APPROVE");
            jdbc.update("UPDATE proposal SET status=?,decision_note=?,decided_by=?,decided_at=? WHERE id=? AND status='PENDING'",
                    approve ? "APPROVED" : "REJECTED", input.note().strip(), actor.id(), now(), proposalId);
            jdbc.update("UPDATE recon_case SET state=?,version=version+1 WHERE id=?", approve ? "RESOLVED" : "OPEN", caseId);
            audit((UUID) row.get("run_id"), caseId, actor, approve ? "PROPOSAL_APPROVED" : "PROPOSAL_REJECTED", input.note().strip());
            return detail(caseId);
        });
    }
    private Map<String, Object> lockedCase(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM recon_case WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw ApiException.missing();
        return rows.get(0);
    }
    private void checkVersion(Map<String, Object> row, long expected) {
        if (((Number) row.get("version")).longValue() != expected) throw ApiException.conflict("This case changed. Refresh it before submitting again.");
    }
    private void validateMutation(Long version, String note) {
        if (version == null || version < 0) throw ApiException.invalid("expectedVersion must be a nonnegative integer.");
        if (note == null || note.indexOf('\0') >= 0 || characters(note.strip()) < 10 || characters(note.strip()) > 2000) throw ApiException.invalid("Note must contain 10 to 2000 characters.");
    }
    static void requireRole(User actor, String role) { if (!actor.role().equals(role)) throw ApiException.forbidden("This action requires the " + role.toLowerCase(java.util.Locale.ROOT) + " role."); }

    Page<Map<String, Object>> audit(int page) {
        return new Page<>(jdbc.query(AUDIT_SELECT + "ORDER BY a.created_at DESC,a.id DESC LIMIT 25 OFFSET ?", this::auditMap, offset(page)),
                jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class), page, 25);
    }
    private Map<String, Object> auditMap(ResultSet rs, int index) throws SQLException {
        return map("id", rs.getObject("id"), "runId", rs.getObject("run_id"), "caseId", rs.getObject("case_id"), "actor", AuthService.user(rs, "actor_"),
                "action", rs.getString("action"), "note", rs.getString("note"), "createdAt", timestamp(rs, "created_at"));
    }
    private void audit(UUID runId, UUID caseId, User actor, String action, String note) {
        jdbc.update("INSERT INTO audit_event(id,run_id,case_id,actor_id,action,note,created_at) VALUES (?,?,?,?,?,?,?)", UUID.randomUUID(), runId, caseId, actor.id(), action, note, now());
    }

    String export(UUID runId) {
        run(runId);
        List<Map<String, Object>> cases = jdbc.query("SELECT * FROM recon_case WHERE run_id=? ORDER BY reference,id", this::caseMap, runId);
        StringWriter output = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(output, CSVFormat.RFC4180)) {
            printer.printRecord("reference", "classification", "differences", "state", "version", "internal_count", "partner_count", "internal_amount", "partner_amount", "delta");
            for (Map<String, Object> row : cases) {
                @SuppressWarnings("unchecked") List<String> differences = (List<String>) row.get("differences");
                printer.printRecord(CsvSources.spreadsheetSafe((String) row.get("reference")), row.get("classification"), String.join(";", differences), row.get("state"), row.get("version"),
                        row.get("internalCount"), row.get("partnerCount"), CsvSources.spreadsheetSafe((String) row.get("internalAmount")),
                        CsvSources.spreadsheetSafe((String) row.get("partnerAmount")), CsvSources.spreadsheetSafe((String) row.get("delta")));
            }
        } catch (IOException e) { throw new IllegalStateException("Could not write CSV export.", e); }
        return output.toString();
    }

    private int offset(int page) { if (page < 0 || page > 100_000) throw ApiException.invalid("Page must be between 0 and 100000."); return page * 25; }
    private static Timestamp now() { return Timestamp.from(Instant.now()); }
    private static int characters(String value) { return value.codePointCount(0, value.length()); }
    private static String timestamp(ResultSet rs, String column) throws SQLException { Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant().toString(); }
    private static String decimal(ResultSet rs, String column) throws SQLException { BigDecimal value = rs.getBigDecimal(column); return value == null ? null : value.setScale(2).toPlainString(); }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
