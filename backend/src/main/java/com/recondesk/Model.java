package com.recondesk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class Model {
    private Model() { }
    record User(UUID id, String name, String email, String role) { }
    record SourceRow(long line, String reference, LocalDate date, String amount, String currency) {
        BigDecimal money() { return new BigDecimal(amount); }
    }
    record Comparison(String reference, String classification, List<String> differences, List<SourceRow> internalRows,
                      List<SourceRow> partnerRows, String internalAmount, String partnerAmount, String delta) { }
    record Page<T>(List<T> items, long total, int page, int pageSize) { }
    record Login(String email, String password) {
        @Override public String toString() { return "Login[email=" + email + ", password=[redacted]]"; }
    }
    record AuthResponse(User user, String csrfToken) {
        @Override public String toString() { return "AuthResponse[user=" + user + ", csrfToken=[redacted]]"; }
    }
    record ProposalInput(Long expectedVersion, String note) { }
    record DecisionInput(Long expectedVersion, String decision, String note) { }
}
