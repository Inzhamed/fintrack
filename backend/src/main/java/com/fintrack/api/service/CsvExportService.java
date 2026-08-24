package com.fintrack.api.service;

import com.fintrack.api.dto.transaction.TransactionFilter;
import com.fintrack.api.exception.ApiException;
import com.fintrack.api.model.Category;
import com.fintrack.api.model.Transaction;
import com.fintrack.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.fintrack.api.repository.TransactionSpecifications.*;

/**
 * Exports transactions as RFC 4180 CSV.
 * <p>
 * Written by hand rather than pulled from a library: the format is four rules, and the two
 * things that actually matter here - Excel's encoding sniffing and formula injection - are
 * not handled by a generic CSV writer anyway.
 */
@Service
@RequiredArgsConstructor
public class CsvExportService {

    /**
     * Excel assumes the system codepage unless a UTF-8 BOM is present, which turns accented
     * merchant names into mojibake. The BOM costs three bytes and is ignored by every other
     * reader that matters.
     */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final String[] HEADERS = {
            "Date", "Type", "Amount", "Currency", "Category", "Description", "Merchant", "Recorded At"
    };

    /**
     * Hard ceiling on one export. The whole result is materialised to build the file, so an
     * unbounded export is a way for one request to exhaust the heap.
     */
    private static final int MAX_ROWS = 50_000;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TransactionRepository transactionRepository;

    /**
     * Renders the transactions matching {@code filter} as CSV bytes.
     * <p>
     * Deliberately reuses the same Specifications as the list endpoint, so an export can
     * never disagree with what the user saw on screen before clicking download.
     */
    @Transactional(readOnly = true)
    public byte[] export(UUID userId, TransactionFilter filter) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw ApiException.validation("'from' must not be after 'to'",
                    Map.of("from", filter.from().toString(), "to", filter.to().toString()));
        }

        List<Specification<Transaction>> predicates = new ArrayList<>();
        predicates.add(ownedBy(userId));
        predicates.add(withCategory());
        predicates.add(ofType(filter.type()));
        predicates.add(occurredOnOrAfter(filter.from()));
        predicates.add(occurredOnOrBefore(filter.to()));
        predicates.add(amountAtLeast(filter.minAmount()));
        predicates.add(amountAtMost(filter.maxAmount()));
        predicates.add(matching(filter.search()));
        predicates.add(filter.wantsUncategorised()
                ? uncategorised()
                : inCategory(filter.categoryId()));
        predicates.removeIf(Objects::isNull);

        List<Transaction> rows = transactionRepository.findAll(
                Specification.allOf(predicates),
                PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Direction.DESC, "occurredOn"))
        ).getContent();

        StringBuilder csv = new StringBuilder(rows.size() * 96 + 128);
        writeRow(csv, HEADERS);

        for (Transaction row : rows) {
            Category category = row.getCategory();
            writeRow(csv, new String[]{
                    row.getOccurredOn().toString(),
                    row.getType().name(),
                    // Plain decimal, no thousands separator and no currency symbol, so the
                    // column parses as a number in every spreadsheet locale.
                    row.getAmount().toPlainString(),
                    row.getCurrency(),
                    category == null ? "" : category.getName(),
                    row.getDescription() == null ? "" : row.getDescription(),
                    row.getMerchant() == null ? "" : row.getMerchant(),
                    row.getCreatedAt() == null ? ""
                            : TIMESTAMP.format(row.getCreatedAt().atZone(java.time.ZoneOffset.UTC))
            });
        }

        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
        return out;
    }

    /** Suggested filename, carrying the range so repeated downloads do not collide. */
    public String fileName(TransactionFilter filter) {
        String from = filter.from() == null ? "all" : filter.from().toString();
        String to = filter.to() == null ? LocalDate.now().toString() : filter.to().toString();
        return "fintrack-transactions-%s-to-%s.csv".formatted(from, to);
    }

    private static void writeRow(StringBuilder out, String[] values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(escape(values[i]));
        }
        // RFC 4180 specifies CRLF. Excel is the reason this matters.
        out.append("\r\n");
    }

    /**
     * Quotes a field per RFC 4180 and defuses spreadsheet formula injection.
     * <p>
     * A description of {@code =1+1} or {@code =HYPERLINK(...)} is a formula to Excel, LibreOffice
     * and Sheets alike - and since descriptions come from whatever the user typed or a bank
     * import supplied, an export can otherwise carry executable content into a spreadsheet.
     * Prefixing with an apostrophe forces it to be read as text. Handled here rather than at
     * write time because the value is stored faithfully; only this rendering is dangerous.
     */
    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String escaped = value;

        char first = escaped.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r') {
            escaped = "'" + escaped;
        }

        // Quote only when required, and double any embedded quote.
        if (escaped.indexOf(',') >= 0 || escaped.indexOf('"') >= 0
                || escaped.indexOf('\n') >= 0 || escaped.indexOf('\r') >= 0) {
            return '"' + escaped.replace("\"", "\"\"") + '"';
        }
        return escaped;
    }
}
