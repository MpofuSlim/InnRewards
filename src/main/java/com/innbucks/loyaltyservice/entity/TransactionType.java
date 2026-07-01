package com.innbucks.loyaltyservice.entity;

public enum TransactionType {
    PURCHASE,
    BILL_PAYMENT,
    QR_PAY,
    WALLET_TOPUP,
    POINTS_PURCHASE,
    PROMO,
    REFUND,
    TRANSFER,
    REDEMPTION,
    ADJUSTMENT,
    /** Card-based purchase at a POS / online checkout. Earns points like PURCHASE. */
    CARD_PAYMENT
}
