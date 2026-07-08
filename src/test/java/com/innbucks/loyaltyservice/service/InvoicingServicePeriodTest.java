package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.Merchant;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link InvoicingService}'s billing-period math.
 *
 * <p>{@code previousPeriodStart/End} are pure — they never touch the injected
 * repositories/props — so the service is constructed with nulls and the methods
 * are exercised directly. Focus is the new DAILY cycle; MONTHLY is asserted as a
 * regression guard.
 */
class InvoicingServicePeriodTest {

    private final InvoicingService svc = new InvoicingService(null, null, null, null, null, null, null);

    @Test
    void dailyPeriodIsTheSingleCompletedDay() {
        LocalDate today = LocalDate.of(2026, 7, 8);       // the day the job runs
        LocalDate yesterday = LocalDate.of(2026, 7, 7);
        assertEquals(yesterday, svc.previousPeriodStart(today, Merchant.BillingCycle.DAILY));
        assertEquals(yesterday, svc.previousPeriodEnd(today, Merchant.BillingCycle.DAILY));
    }

    @Test
    void dailyPeriodCrossesMonthBoundary() {
        LocalDate firstOfMonth = LocalDate.of(2026, 7, 1);
        LocalDate lastOfPrevMonth = LocalDate.of(2026, 6, 30);
        assertEquals(lastOfPrevMonth, svc.previousPeriodStart(firstOfMonth, Merchant.BillingCycle.DAILY));
        assertEquals(lastOfPrevMonth, svc.previousPeriodEnd(firstOfMonth, Merchant.BillingCycle.DAILY));
    }

    @Test
    void monthlyPeriodIsThePreviousCalendarMonth() {
        LocalDate today = LocalDate.of(2026, 7, 8);
        assertEquals(LocalDate.of(2026, 6, 1), svc.previousPeriodStart(today, Merchant.BillingCycle.MONTHLY));
        assertEquals(LocalDate.of(2026, 6, 30), svc.previousPeriodEnd(today, Merchant.BillingCycle.MONTHLY));
    }
}
