package com.recondesk;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static com.recondesk.Model.*;

@Component
class CsvSources {
    static final int MAX_BYTES = 1_048_576;
    private static final List<String> HEADER = List.of("reference", "date", "amount", "currency");
    private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false)
            .setLenientEof(false).setTrailingData(false).get();

    List<SourceRow> parse(byte[] bytes, String source) {
        if (bytes.length > MAX_BYTES) throw new ApiException(413, "UPLOAD_LIMIT", source + " exceeds 1 MiB.");
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw ApiException.invalid(source + " must use valid UTF-8."); }
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        validateQuotes(content, source);
        List<SourceRow> rows = new ArrayList<>();
        try (CSVParser parser = FORMAT.parse(new StringReader(content))) {
            Iterator<CSVRecord> records = parser.iterator();
            if (!records.hasNext() || !values(records.next()).equals(HEADER))
                throw ApiException.invalid(source + " needs the exact header reference,date,amount,currency.");
            while (records.hasNext()) {
                CSVRecord record = records.next();
                long line = record.getRecordNumber();
                if (rows.size() >= 1000) throw ApiException.invalid(source + " exceeds 1000 data rows.");
                if (record.size() != 4) throw rowError(source, line, "must contain exactly four columns");
                String reference = record.get(0).strip();
                String dateText = record.get(1);
                String amountText = record.get(2);
                String currency = record.get(3);
                if (!reference.matches("[A-Za-z0-9._/-]{1,64}")) throw rowError(source, line, "has an invalid reference");
                if (!dateText.matches("\\d{4}-\\d{2}-\\d{2}")) throw rowError(source, line, "needs an ISO date");
                LocalDate date;
                try { date = LocalDate.parse(dateText); }
                catch (DateTimeParseException e) { throw rowError(source, line, "has an invalid calendar date"); }
                if (date.getYear() == 0) throw rowError(source, line, "date year must be between 0001 and 9999");
                if (!amountText.matches("[0-9]+(\\.[0-9]{1,2})?")) throw rowError(source, line, "has an invalid amount");
                int decimalPoint = amountText.indexOf('.');
                int wholeEnd = decimalPoint < 0 ? amountText.length() : decimalPoint;
                int firstDigit = 0;
                while (firstDigit < wholeEnd - 1 && amountText.charAt(firstDigit) == '0') firstDigit++;
                if (wholeEnd - firstDigit > 12) throw rowError(source, line, "amount is too large");
                BigDecimal amount = new BigDecimal(amountText.substring(firstDigit)).setScale(2);
                if (amount.compareTo(new BigDecimal("999999999999.99")) > 0) throw rowError(source, line, "amount is too large");
                if (!List.of("IDR", "USD").contains(currency)) throw rowError(source, line, "currency must be IDR or USD");
                rows.add(new SourceRow(line, reference, date, amount.toPlainString(), currency));
            }
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) { throw ApiException.invalid(source + " contains malformed CSV."); }
        if (rows.isEmpty()) throw ApiException.invalid(source + " must contain at least one data row.");
        return List.copyOf(rows);
    }

    private List<String> values(CSVRecord record) { return record.stream().toList(); }
    private void validateQuotes(String content, String source) {
        boolean quoted = false, fieldStart = true, closedQuote = false;
        for (int i = 0; i < content.length(); i++) {
            char value = content.charAt(i);
            if (quoted) {
                if (value == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') i++;
                    else { quoted = false; closedQuote = true; }
                }
            } else if (value == ',' || value == '\r' || value == '\n') {
                fieldStart = true; closedQuote = false;
            } else if (value == '"' && fieldStart) {
                quoted = true; fieldStart = false;
            } else {
                if (value == '"' || closedQuote) throw ApiException.invalid(source + " contains malformed CSV quoting.");
                fieldStart = false;
            }
        }
        if (quoted) throw ApiException.invalid(source + " contains an unclosed quoted field.");
    }
    private ApiException rowError(String source, long line, String message) {
        return ApiException.invalid(source + " record " + line + " " + message + ".");
    }

    List<Comparison> compare(List<SourceRow> internal, List<SourceRow> partner) {
        Map<String, List<SourceRow>> a = internal.stream().collect(Collectors.groupingBy(SourceRow::reference));
        Map<String, List<SourceRow>> b = partner.stream().collect(Collectors.groupingBy(SourceRow::reference));
        TreeMap<String, Boolean> references = new TreeMap<>();
        a.keySet().forEach(r -> references.put(r, true));
        b.keySet().forEach(r -> references.put(r, true));
        List<Comparison> results = new ArrayList<>();
        for (String reference : references.keySet()) {
            List<SourceRow> ar = a.getOrDefault(reference, List.of()), br = b.getOrDefault(reference, List.of());
            List<String> differences = new ArrayList<>();
            String classification;
            boolean duplicate = ar.size() > 1 || br.size() > 1;
            if (duplicate) classification = "DUPLICATE";
            else if (ar.isEmpty()) classification = "PARTNER_ONLY";
            else if (br.isEmpty()) classification = "INTERNAL_ONLY";
            else {
                if (ar.get(0).money().compareTo(br.get(0).money()) != 0) differences.add("AMOUNT");
                if (!ar.get(0).date().equals(br.get(0).date())) differences.add("DATE");
                if (!ar.get(0).currency().equals(br.get(0).currency())) differences.add("CURRENCY");
                classification = differences.isEmpty() ? "MATCHED" : "DIFFERENT";
            }
            String ia = duplicate || ar.isEmpty() ? null : ar.get(0).amount();
            String pa = duplicate || br.isEmpty() ? null : br.get(0).amount();
            boolean mixedCurrencies = !ar.isEmpty() && !br.isEmpty() && !ar.get(0).currency().equals(br.get(0).currency());
            String delta = duplicate || mixedCurrencies ? null : new BigDecimal(ia == null ? "0.00" : ia)
                    .subtract(new BigDecimal(pa == null ? "0.00" : pa)).setScale(2).toPlainString();
            results.add(new Comparison(reference, classification, List.copyOf(differences), ar, br, ia, pa, delta));
        }
        return results;
    }

    static String spreadsheetSafe(String value) {
        if (value == null) return "";
        String stripped = value.stripLeading();
        if (!stripped.isEmpty() && "=+-@\t\r\n".indexOf(stripped.charAt(0)) >= 0) return "'" + value;
        if (!value.isEmpty() && "\t\r\n".indexOf(value.charAt(0)) >= 0) return "'" + value;
        return value;
    }
}
