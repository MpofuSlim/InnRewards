package com.innbucks.loyaltyservice.config;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Propagates the inbound {@code X-Correlation-Id} (mirrored to MDC by
 * {@link CorrelationIdFilter}) onto every outbound RestClient call so a single
 * traceId follows a request across service boundaries. Without this the
 * receiving service mints a fresh ID and the two halves of the trace can't
 * be joined.
 *
 * <p>If the caller already set {@code X-Correlation-Id} on the request (rare,
 * but possible for one-off tooling) we don't overwrite it. If there's nothing
 * in MDC (e.g. a scheduled job with no inbound request) we just pass through —
 * the downstream service will generate its own ID, matching today's behaviour.
 */
public class CorrelationIdPropagatingInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (request.getHeaders().getFirst(CorrelationIdFilter.HEADER) == null) {
            String traceId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (traceId != null && !traceId.isBlank()) {
                request.getHeaders().add(CorrelationIdFilter.HEADER, traceId);
            }
        }
        return execution.execute(request, body);
    }
}
