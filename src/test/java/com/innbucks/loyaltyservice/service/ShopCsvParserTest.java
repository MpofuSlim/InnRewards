package com.innbucks.loyaltyservice.service;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CSV reader sits inside ShopService rather than its own utility class
 * — it's small enough that pulling it out felt over-engineered. The tests
 * exercise the corner cases (quoted commas, escaped quotes, CRLF, blank
 * lines, trailing-no-newline) directly through {@link ShopService#parseCsv}.
 */
class ShopCsvParserTest {

    @Test
    void parsesSimpleCsv() throws IOException {
        List<String[]> rows = parse("""
                name,address
                Pizza Inn Avondale,123 King Rd
                Pizza Inn Westgate,456 Queen Rd
                """);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsExactly("name", "address");
        assertThat(rows.get(1)).containsExactly("Pizza Inn Avondale", "123 King Rd");
        assertThat(rows.get(2)).containsExactly("Pizza Inn Westgate", "456 Queen Rd");
    }

    @Test
    void preservesCommasInsideQuotedFields() throws IOException {
        // Real shop addresses routinely embed commas. A naive split-on-comma
        // parser would shred them into three columns.
        List<String[]> rows = parse("""
                name,address
                Pizza Inn Avondale,"123 King George Rd, Avondale, Harare"
                """);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly(
                "Pizza Inn Avondale",
                "123 King George Rd, Avondale, Harare");
    }

    @Test
    void handlesEscapedQuotesInsideQuotedFields() throws IOException {
        // RFC 4180: a literal " inside a quoted field is escaped as "".
        List<String[]> rows = parse("""
                name,address
                "Tony's ""Best"" Bites","42 Main"
                """);

        assertThat(rows.get(1)).containsExactly("Tony's \"Best\" Bites", "42 Main");
    }

    @Test
    void handlesCrlfAndSkipsBlankLines() throws IOException {
        String csv = "name,address\r\nAlpha,A1\r\n\r\nBeta,B1\r\n";
        List<String[]> rows = parse(csv);

        assertThat(rows).hasSize(3); // header + two data rows; blank line dropped
        assertThat(rows.get(2)[0]).isEqualTo("Beta");
    }

    @Test
    void acceptsTrailingRowWithoutNewline() throws IOException {
        List<String[]> rows = parse("name,address\nLast,LastAddr");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("Last", "LastAddr");
    }

    @Test
    void returnsEmptyListForFullyBlankInput() throws IOException {
        assertThat(parse("")).isEmpty();
        assertThat(parse("\n\n\n")).isEmpty();
    }

    private static List<String[]> parse(String csv) throws IOException {
        return ShopService.parseCsv(new BufferedReader(new StringReader(csv)));
    }
}
