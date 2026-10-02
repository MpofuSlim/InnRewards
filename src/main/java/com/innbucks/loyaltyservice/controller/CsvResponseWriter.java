package com.innbucks.loyaltyservice.controller;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.Writer;

/**
 * Streams a CSV export straight into the servlet response, and commits the
 * download headers only on the FIRST write.
 *
 * <p>The exports used to build the whole file as one String and return it, so a
 * busy period sat in memory twice (the String and the entities behind it) before
 * a byte left. Writing page by page keeps one page in memory instead.
 *
 * <p><b>Why the headers wait for the first write.</b> The report services raise
 * every refusal (bad range, unknown scope, a merchant or shop the caller does not
 * own) before they write anything. While nothing has been written, the response
 * is untouched, so {@code GlobalExceptionHandler} answers those refusals with its
 * usual JSON error and status. Setting {@code Content-Disposition} up front would
 * have made the browser save that error as {@code transactions.csv}. Once the
 * header row is out, the response is committed; a failure after that point
 * (normally the client going away) truncates the file, which is the only thing
 * a streamed download can do.
 */
final class CsvResponseWriter extends Writer {

    private final HttpServletResponse response;
    private final String filename;
    private Writer delegate;

    CsvResponseWriter(HttpServletResponse response, String filename) {
        this.response = response;
        this.filename = filename;
    }

    private Writer delegate() throws IOException {
        if (delegate == null) {
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            delegate = response.getWriter();
        }
        return delegate;
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        delegate().write(cbuf, off, len);
    }

    @Override
    public void write(String str, int off, int len) throws IOException {
        delegate().write(str, off, len);
    }

    @Override
    public void flush() throws IOException {
        if (delegate != null) delegate.flush();
    }

    @Override
    public void close() throws IOException {
        flush();
    }
}
