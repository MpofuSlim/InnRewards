package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.exception.LoyaltyException;

import java.time.Instant;

/**
 * Paging and range rules shared by every support list.
 *
 * <p>Pagination is FORGIVING — a negative page is the first page, a size
 * outside 1..{@value #MAX_SIZE} is clamped — because a wrong page only shows a
 * different slice of the same answer. A date RANGE is not: an inverted
 * {@code from}/{@code to} would return an empty page that reads as "nothing
 * happened", so it is a 400.
 */
public final class SupportPaging {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private SupportPaging() {}

    public static int page(int page) {
        return Math.max(page, 0);
    }

    public static int size(int size) {
        if (size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    public static void requireOrderedRange(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw LoyaltyException.badRequest("invalid_range", "'from' must be before 'to'.");
        }
    }
}
