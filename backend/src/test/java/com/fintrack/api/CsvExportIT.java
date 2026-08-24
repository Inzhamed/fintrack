package com.fintrack.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CsvExportIT extends AbstractIntegrationTest {

    private static final String BOM = "﻿";

    private String exportCsv(String token, String query) throws Exception {
        MvcResult result = mockMvc.perform(authed(get("/api/v1/transactions/export" + query), token))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the response is a downloadable UTF-8 CSV with a BOM and a dated filename")
    void servesDownloadableFile() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        createTransaction(token, """
                {"type":"EXPENSE","amount":100,"occurredOn":"2026-08-05"}""");

        MvcResult result = mockMvc.perform(
                        authed(get("/api/v1/transactions/export?from=2026-08-01&to=2026-08-31"), token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"fintrack-transactions-2026-08-01-to-2026-08-31.csv\""))
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        // Without the BOM, Excel reads the file in the system codepage and mangles accents.
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);

        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(csv).startsWith(BOM + "Date,Type,Amount,Currency,Category,Description,Merchant,Recorded At\r\n");
        // RFC 4180 line endings.
        assertThat(csv).contains("\r\n");
    }

    @Test
    @DisplayName("a value that looks like a formula is neutralised for spreadsheets")
    void defusesFormulaInjection() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        // Each of these is executable if a spreadsheet reads it as a formula.
        createTransaction(token, """
                {"type":"EXPENSE","amount":10,"description":"=HYPERLINK(\\"http://evil\\",\\"click\\")","occurredOn":"2026-08-20"}""");
        createTransaction(token, """
                {"type":"EXPENSE","amount":20,"description":"+15551234","occurredOn":"2026-08-21"}""");
        createTransaction(token, """
                {"type":"EXPENSE","amount":30,"description":"-1+1","occurredOn":"2026-08-22"}""");
        createTransaction(token, """
                {"type":"EXPENSE","amount":40,"description":"@SUM(A1:A9)","occurredOn":"2026-08-23"}""");

        String csv = exportCsv(token, "?from=2026-08-01&to=2026-08-31");

        // Every dangerous leading character is prefixed with an apostrophe, so the cell is
        // read as text. The stored value itself is untouched — only this rendering is risky.
        assertThat(csv).contains("'=HYPERLINK");
        assertThat(csv).contains("'+15551234");
        assertThat(csv).contains("'-1+1");
        assertThat(csv).contains("'@SUM(A1:A9)");

        // And no field begins with a bare formula character.
        for (String line : csv.split("\r\n")) {
            for (String field : line.split(",")) {
                String bare = field.replace("\"", "").replace(BOM, "");
                assertThat(bare.startsWith("=") || bare.startsWith("@"))
                        .as("field %s in line %s", field, line)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("commas, quotes and non-ASCII survive the round trip")
    void escapesPerRfc4180() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        createTransaction(token, """
                {"type":"EXPENSE","amount":10,"description":"He said \\"hello\\"","merchant":"Café, Alger","occurredOn":"2026-08-10"}""");

        String csv = exportCsv(token, "?from=2026-08-01&to=2026-08-31");

        // Embedded quotes are doubled and the field is wrapped.
        assertThat(csv).contains("\"He said \"\"hello\"\"\"");
        // A comma inside a value forces quoting, and the accent stays intact.
        assertThat(csv).contains("\"Café, Alger\"");
    }

    @Test
    @DisplayName("the export honours the same filters as the list endpoint")
    void appliesFilters() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        UUID groceries = globalCategoryId(token, "Groceries");
        UUID salary = globalCategoryId(token, "Salary");

        createTransaction(token, """
                {"type":"EXPENSE","amount":100,"categoryId":"%s","description":"InRange","occurredOn":"2026-08-05"}
                """.formatted(groceries));
        createTransaction(token, """
                {"type":"INCOME","amount":900,"categoryId":"%s","description":"Wages","occurredOn":"2026-08-06"}
                """.formatted(salary));
        createTransaction(token, """
                {"type":"EXPENSE","amount":700,"description":"OutOfRange","occurredOn":"2026-07-05"}""");

        String august = exportCsv(token, "?from=2026-08-01&to=2026-08-31");
        assertThat(august).contains("InRange").contains("Wages").doesNotContain("OutOfRange");

        String expensesOnly = exportCsv(token, "?from=2026-08-01&to=2026-08-31&type=EXPENSE");
        assertThat(expensesOnly).contains("InRange").doesNotContain("Wages");

        String searched = exportCsv(token, "?search=OutOfRange");
        assertThat(searched).contains("OutOfRange").doesNotContain("InRange");
    }

    @Test
    @DisplayName("an export never includes another user's rows")
    void isolatesUsers() throws Exception {
        String hamed = registerAndLogin("hamed@example.com");
        createTransaction(hamed, """
                {"type":"EXPENSE","amount":999,"description":"PrivateToHamed","occurredOn":"2026-08-05"}""");

        String other = registerAndLogin("someone-else@example.com");
        createTransaction(other, """
                {"type":"EXPENSE","amount":111,"description":"PrivateToOther","occurredOn":"2026-08-05"}""");

        assertThat(exportCsv(other, ""))
                .contains("PrivateToOther")
                .doesNotContain("PrivateToHamed");
    }

    @Test
    @DisplayName("an empty result still returns a valid file with just the header")
    void exportsHeaderOnlyWhenEmpty() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        String csv = exportCsv(token, "?from=2020-01-01&to=2020-01-31");

        assertThat(csv).isEqualTo(BOM
                + "Date,Type,Amount,Currency,Category,Description,Merchant,Recorded At\r\n");
    }

    @Test
    @DisplayName("amounts are plain decimals a spreadsheet will read as numbers")
    void writesParseableAmounts() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        createTransaction(token, """
                {"type":"EXPENSE","amount":1234567.89,"occurredOn":"2026-08-05"}""");

        String csv = exportCsv(token, "?from=2026-08-01&to=2026-08-31");

        // No thousands separator, no currency symbol, no scientific notation.
        assertThat(csv).contains(",1234567.89,");
    }

    @Test
    @DisplayName("export requires authentication")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/export"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an inverted range is rejected")
    void rejectsInvertedRange() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        mockMvc.perform(authed(get("/api/v1/transactions/export?from=2026-09-01&to=2026-08-01"), token))
                .andExpect(status().isBadRequest());
    }
}
