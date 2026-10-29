package com.recondesk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvSourcesTest {
    private final CsvSources csv = new CsvSources();
    private static final String HEADER = "reference,date,amount,currency\r\n";
    private List<Model.SourceRow> parse(String text) { return csv.parse(text.getBytes(StandardCharsets.UTF_8), "Test source"); }

    @Test
    void supportsBomQuotedFieldsAndExactAmounts() {
        var rows = parse("\uFEFF" + HEADER + "\"  INV/001  \",2026-10-01,1.2,USD\r\nMAX,2026-10-02,999999999999.99,IDR\r\n");
        assertEquals(2, rows.size());
        assertEquals("INV/001", rows.get(0).reference());
        assertEquals("1.20", rows.get(0).amount());
        assertEquals(2, rows.get(0).line());
        assertEquals("999999999999.99", rows.get(1).amount());
        assertEquals("1.00", parse(HEADER + "ZERO,2026-10-01,0000000000000001,USD\n").get(0).amount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "reference,date,amount,currency\n", "date,reference,amount,currency\nA,2026-01-01,1,USD\n",
            "reference,date,amount,currency,extra\nA,2026-01-01,1,USD,x\n", "reference,date,amount,currency\n\n",
            "reference,date,amount,currency\nA,2026-02-30,1,USD\n", "reference,date,amount,currency\nA,2026-1-01,1,USD\n",
            "reference,date,amount,currency\nA,0000-01-01,1,USD\n",
            "reference,date,amount,currency\nA,2026-01-01,-1,USD\n", "reference,date,amount,currency\nA,2026-01-01,1e2,USD\n",
            "reference,date,amount,currency\nA,2026-01-01,1.001,USD\n", "reference,date,amount,currency\nA,2026-01-01,1000000000000,USD\n",
            "reference,date,amount,currency\nA,2026-01-01,1,EUR\n", "reference,date,amount,currency\nA,2026-01-01,1,usd\n",
            "reference,date,amount,currency\nA,2026-01-01,1\n", "reference,date,amount,currency\nA,2026-01-01,1,USD,extra\n",
            "reference,date,amount,currency\n\"A,2026-01-01,1,USD", "reference,date,amount,currency\n\"A\"oops,2026-01-01,1,USD\n",
            "reference,date,amount,currency\nA\"oops,2026-01-01,1,USD\n",
            "reference,date,amount,currency\n=SUM(A1),2026-01-01,1,USD\n"})
    void rejectsInvalidSources(String value) { assertThrows(ApiException.class, () -> parse(value)); }

    @Test
    void rejectsMalformedUtf8AndLimits() {
        assertThrows(ApiException.class, () -> csv.parse(new byte[]{(byte) 0xc3, (byte) 0x28}, "Test source"));
        ApiException limit = assertThrows(ApiException.class, () -> csv.parse(new byte[CsvSources.MAX_BYTES + 1], "Test source"));
        assertEquals(413, limit.status);
        assertThrows(ApiException.class, () -> parse(HEADER + "A,2026-10-01,1,USD\n".repeat(1001)));
        assertEquals(1000, parse(HEADER + "A,2026-10-01,1,USD\n".repeat(1000)).size());
    }

    @Test
    void groupsByReferenceAndRetainsEveryDuplicate() {
        var a = parse(HEADER + "A,2026-10-01,10,USD\nB,2026-10-02,10,USD\nD,2026-10-01,1,USD\nD,2026-10-01,2,USD\nI,2026-10-01,5,USD\n");
        var b = parse(HEADER + "A,2026-10-01,10.00,USD\nB,2026-10-03,11,IDR\nD,2026-10-01,1,USD\nP,2026-10-01,5,USD\n");
        var results = csv.compare(a, b);
        assertEquals(List.of("MATCHED", "DIFFERENT", "DUPLICATE", "INTERNAL_ONLY", "PARTNER_ONLY"), results.stream().map(Model.Comparison::classification).toList());
        assertEquals(List.of("AMOUNT", "DATE", "CURRENCY"), results.get(1).differences());
        assertNull(results.get(1).delta());
        assertEquals(2, results.get(2).internalRows().size());
        assertNull(results.get(2).internalAmount());
        assertNull(results.get(2).partnerAmount());
        assertNull(results.get(2).delta());
        assertEquals("5.00", results.get(3).delta());
        assertEquals("-5.00", results.get(4).delta());
        assertEquals("-0.01", csv.compare(parse(HEADER + "A,2026-10-01,0.10,USD\n"), parse(HEADER + "A,2026-10-01,0.11,USD\n")).get(0).delta());
    }

    @Test
    void protectsFormulaPrefixesAndHashesUnambiguousOriginalBytes() {
        for (String value : List.of("=1+1", "+1", "-1", "@SUM(A1)", "  =1", "\t=1", "\ntext"))
            assertTrue(CsvSources.spreadsheetSafe(value).startsWith("'"));
        assertEquals("INV-1", CsvSources.spreadsheetSafe("INV-1"));
        byte[] a = {1}, b = {2, 3}, c = {1, 2}, d = {3};
        assertNotEquals(AuthService.payloadHash("Run", a, b), AuthService.payloadHash("Run", c, d));
        assertNotEquals(AuthService.payloadHash("Run", a, b), AuthService.payloadHash("Run ", a, b));
    }
}
