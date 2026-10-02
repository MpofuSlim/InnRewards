package com.innbucks.loyaltyservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CSV exports stream into the response. The download headers must wait for
 * the first write, so a refusal raised before any write still reaches the client
 * as the ordinary JSON error rather than as a file called transactions.csv.
 */
class CsvResponseWriterTest {

    @Test
    void nothingWritten_leavesTheResponseUntouched() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsvResponseWriter out = new CsvResponseWriter(response, "transactions.csv");

        out.close(); // what the controllers' try-with-resources does when a refusal is thrown first

        assertThat(response.getHeader("Content-Disposition")).isNull();
        assertThat(response.getContentType()).isNull();
        assertThat(response.isCommitted()).isFalse();
    }

    @Test
    void theFirstWrite_setsTheDownloadHeaders_andTheBodyFollows() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsvResponseWriter out = new CsvResponseWriter(response, "vouchers.csv");

        out.append("id,code\n");
        out.append("1,9087-8765-9876-4566\n");
        out.flush();

        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"vouchers.csv\"");
        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertThat(response.getContentAsString()).isEqualTo("id,code\n1,9087-8765-9876-4566\n");
    }

    @Test
    void nonAsciiNames_surviveAsUtf8() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsvResponseWriter out = new CsvResponseWriter(response, "vouchers.csv");

        out.append("Chipo Ndlovu-Ncubé\n");
        out.flush();

        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("Chipo Ndlovu-Ncubé\n");
    }
}
