package com.innbucks.loyaltyservice.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bill's {@code currency} on a voucher redeem is optional. When sent it must
 * be a 3-letter code (any case, surrounding spaces ignored, blank = not sent),
 * so a typo is a plain {@code 400 Validation failed} naming the field, never a
 * {@code CURRENCY_MISMATCH} that reads as a fact about the voucher.
 */
class RedeemVoucherRequestCurrencyTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Dtos.RedeemVoucherRequest withCurrency(String currency) {
        return new Dtos.RedeemVoucherRequest(UUID.randomUUID(), "4829137605128368", null, null, null, null,
                new BigDecimal("20.00"), "RCPT-1", currency);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"USD", "zwg", " Zar ", "", "   "})
    void aThreeLetterCode_orNothing_isAccepted(String currency) {
        assertThat(validator.validate(withCurrency(currency))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"US", "USDT", "US$", "840", "U S D", "ZiG1"})
    void anythingElse_isRefusedOnTheCurrencyField(String currency) {
        Set<ConstraintViolation<Dtos.RedeemVoucherRequest>> violations = validator.validate(withCurrency(currency));

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.getPropertyPath()).hasToString("currency");
            assertThat(v.getMessage()).isEqualTo("must be a 3-letter currency code, e.g. USD");
        });
    }

    @Test
    void theOlderShapes_sendNoCurrency() {
        assertThat(new Dtos.RedeemVoucherRequest(null, "C", null, null, null, null).currency()).isNull();
        assertThat(new Dtos.RedeemVoucherRequest(null, "C", null, null, null, null, BigDecimal.ONE, "R")
                .currency()).isNull();
    }
}
