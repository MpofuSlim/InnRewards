package com.innbucks.loyaltyservice.exception;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.config.VoucherStatusConverter;
import com.innbucks.loyaltyservice.entity.Voucher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A request parameter the client spelled wrong is a 400, not a 500.
 *
 * <p>This is about the RESOLVER CHAIN, not about any one endpoint, which is why
 * it runs against a stub controller: {@code @ExceptionHandler} methods are
 * consulted before Spring's {@code DefaultHandlerExceptionResolver}, so
 * {@link GlobalExceptionHandler}'s {@code Exception} catch-all was shadowing
 * Spring's own 400 for {@code MethodArgumentTypeMismatchException} and every
 * unconvertible parameter came back as "Something went wrong on our end. Please
 * try again." — a message that invites a retry which cannot succeed, on an
 * endpoint that was working fine. Asserting the handler in isolation (as
 * {@link GlobalExceptionHandlerTest} does) cannot catch that: the bug was in
 * which handler Spring picks, so the test has to go through Spring's dispatch.
 *
 * <p>It also pins the V48 status alias end-to-end, through real parameter
 * binding rather than a direct call on the converter.
 */
class GlobalExceptionHandlerParameterBindingTest {

    /**
     * Stands in for the seven real endpoints that take a {@code Voucher.Status}
     * query parameter and the many that take a UUID path variable. A stub keeps
     * the test about binding and error mapping, with no security, tenancy or
     * repository setup to fail for unrelated reasons.
     */
    @RestController
    static class ProbeController {

        @GetMapping("/probe/vouchers")
        String byStatus(@RequestParam("status") Voucher.Status status) {
            return status.name();
        }

        @GetMapping("/probe/vouchers/{id}")
        String byId(@PathVariable("id") UUID id) {
            return id.toString();
        }

        @GetMapping("/probe/required")
        String required(@RequestParam("since") String since) {
            return since;
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // The converter is registered the same way Spring Boot registers it in
        // production (as a Converter bean feeding the MVC conversion service),
        // so the alias is exercised through real binding here.
        FormattingConversionService conversion = new DefaultFormattingConversionService();
        conversion.addConverter(new VoucherStatusConverter(new LoyaltyMetrics(new SimpleMeterRegistry())));
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setConversionService(conversion)
                .build();
    }

    @Test
    void aLiveStatusBinds() throws Exception {
        mvc.perform(get("/probe/vouchers").param("status", "PARTIALLY_USED"))
                .andExpect(status().isOk())
                .andExpect(content().string("PARTIALLY_USED"));
    }

    @Test
    void theRetiredDeliveredStatusBindsToIssued() throws Exception {
        // The console's existing tab, through the whole stack: it keeps working
        // across the deploy instead of erroring on a value V48 retired.
        mvc.perform(get("/probe/vouchers").param("status", "DELIVERED"))
                .andExpect(status().isOk())
                .andExpect(content().string("ISSUED"));
    }

    @Test
    void anUnknownStatusIs400_namingTheParameterAndTheAcceptedValues() throws Exception {
        mvc.perform(get("/probe/vouchers").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400 BAD_REQUEST"))
                // Named parameter + accepted values: a client cannot fix a
                // rejected value it is never told the shape of.
                .andExpect(jsonPath("$.message").value(
                        "Invalid value for 'status'. Accepted values: "
                                + "ISSUED, VIEWED, REDEEMED, PARTIALLY_USED, EXPIRED, REVOKED."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void aBlankStatusIs400_andSaysWhatToDoAboutIt() throws Exception {
        // A filter UI's "All" tab naturally sends `?status=`, and this was a
        // 500 — measured, and NOT caused by the converter: an empty string
        // converts to null on both the default and the custom path (Spring's
        // enum converter factory returns null outright; TypeConverterDelegate
        // reaches the same answer for ours by catching the refusal and applying
        // its empty-enum-identifier rule), and
        // RequestParamMethodArgumentResolver then rejects a required parameter
        // that is "present but converted to null". A different exception type
        // from the unknown-value case above, hence a second handler.
        mvc.perform(get("/probe/vouchers").param("status", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400 BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "Parameter 'status' was sent with no value. "
                                + "Give it a value, or omit the parameter entirely."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void anAbsentRequiredParameterIs400_withADifferentMessageToABlankOne() throws Exception {
        // The two need different fixes — send it, versus stop sending it empty —
        // so they must not collapse into one message.
        mvc.perform(get("/probe/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required parameter 'since' is missing."));
    }

    @Test
    void theRejectedValueIsNotEchoedBack() throws Exception {
        // It is caller-controlled, so reflecting it into the response body is a
        // gift to anyone probing for one.
        mvc.perform(get("/probe/vouchers").param("status", "<script>alert(1)</script>"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("script"))));
    }

    @Test
    void aMalformedPathVariableIs400_evenWhenTheTargetIsNotAnEnum() throws Exception {
        // Same handler, non-enum branch: no accepted-values list to offer, so
        // the message just names the parameter. Previously a 500 on every
        // mistyped id in the service.
        mvc.perform(get("/probe/vouchers/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'id'."));
    }
}
