package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Voucher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link VoucherService#toResponse} field by field.
 *
 * <p><b>Why this test has to be exhaustive.</b> {@code VoucherResponse} is a
 * record built POSITIONALLY from a 28-component canonical constructor, and it
 * carries several runs of adjacent same-typed components — three UUIDs, four
 * Strings, six Instants. Swapping any two neighbours within a run COMPILES
 * CLEANLY and the compiler can never catch it; the symptom is a console row
 * quietly showing the issuer's phone as the sender's, or the redeemed time in
 * the viewed column. So every component gets a DISTINCT value here and is
 * asserted individually — a shared fixture value (two fields both "+263..."
 * or both {@code Instant.now()}) would let exactly the swap this guards
 * against pass.
 */
class VoucherResponseMappingTest {

    // One distinct value per field. Never reuse — see the class javadoc.
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID MERCHANT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID SHOP = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID BATCH = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    private static final UUID ASSIGNED_USER = UUID.fromString("00000000-0000-0000-0000-0000000000a5");
    private static final UUID ISSUER_USER = UUID.fromString("00000000-0000-0000-0000-0000000000a6");
    private static final UUID TRANSFERRED_FROM = UUID.fromString("00000000-0000-0000-0000-0000000000a7");

    private static final Instant ISSUED = Instant.parse("2026-09-01T01:00:00Z");
    private static final Instant DELIVERED = Instant.parse("2026-09-02T02:00:00Z");
    private static final Instant VIEWED = Instant.parse("2026-09-03T03:00:00Z");
    private static final Instant REDEEMED = Instant.parse("2026-09-04T04:00:00Z");
    private static final Instant TRANSFERRED = Instant.parse("2026-09-05T05:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-09-06T06:00:00Z");

    private static Voucher fullyPopulated() {
        Voucher v = new Voucher();
        v.setId(ID);
        v.setCode("K7M2PQ9XR4TB");
        v.setStatus(Voucher.Status.PARTIALLY_USED);
        v.setVoucherType(Voucher.VoucherType.SINGLE_USE);
        v.setMerchantId(MERCHANT);
        v.setShopId(SHOP);
        v.setBatchId(BATCH);
        v.setCampaignSource("spring-2026");
        v.setAssignedUserId(ASSIGNED_USER);
        v.setAssigneePhone("+263786546765");
        v.setAssigneeName("Sedrick Nyanyiwa");
        v.setSenderName("Tawanda Mpofu");
        v.setSenderPhone("+263782608767");
        v.setIssuerUserId(ISSUER_USER);
        v.setIssuerPhone("+263772000111");
        v.setIssuerEmail("shopadmin@westgate.co.zw");
        v.setUsesRemaining(1);
        v.setValue(new BigDecimal("5.0000"));
        v.setCurrency("USD");
        v.setBaseValue(new BigDecimal("4.5000"));
        v.setIssuedAt(ISSUED);
        v.setDeliveredAt(DELIVERED);
        v.setViewedAt(VIEWED);
        v.setRedeemedAt(REDEEMED);
        v.setTransferredAt(TRANSFERRED);
        v.setExpiresAt(EXPIRES);
        v.setTransferredFromUserId(TRANSFERRED_FROM);
        v.setTransferredFromPhone("+263771234567");
        return v;
    }

    @Test
    void everyStoredFieldReachesTheResponse_inTheRightSlot() {
        Dtos.VoucherResponse r = VoucherService.toResponse(fullyPopulated());

        assertThat(r.id()).isEqualTo(ID);
        assertThat(r.code()).isEqualTo("K7M2PQ9XR4TB");
        assertThat(r.status()).isEqualTo("PARTIALLY_USED");
        assertThat(r.voucherType()).isEqualTo("SINGLE_USE");

        assertThat(r.merchantId()).isEqualTo(MERCHANT);
        assertThat(r.shopId()).isEqualTo(SHOP);
        assertThat(r.batchId()).isEqualTo(BATCH);
        assertThat(r.campaignSource()).isEqualTo("spring-2026");

        assertThat(r.assignedUserId()).isEqualTo(ASSIGNED_USER);
        assertThat(r.assigneePhone()).isEqualTo("+263786546765");
        assertThat(r.assigneeName()).isEqualTo("Sedrick Nyanyiwa");

        assertThat(r.senderName()).isEqualTo("Tawanda Mpofu");
        assertThat(r.senderPhone()).isEqualTo("+263782608767");

        assertThat(r.issuerUserId()).isEqualTo(ISSUER_USER);
        assertThat(r.issuerPhone()).isEqualTo("+263772000111");
        assertThat(r.issuerEmail()).isEqualTo("shopadmin@westgate.co.zw");

        assertThat(r.usesRemaining()).isEqualTo(1);
        assertThat(r.value()).isEqualByComparingTo("5.0000");
        assertThat(r.currency()).isEqualTo("USD");
        assertThat(r.baseValue()).isEqualByComparingTo("4.5000");

        assertThat(r.issuedAt()).isEqualTo(ISSUED);
        assertThat(r.deliveredAt()).isEqualTo(DELIVERED);
        assertThat(r.viewedAt()).isEqualTo(VIEWED);
        assertThat(r.redeemedAt()).isEqualTo(REDEEMED);
        assertThat(r.transferredAt()).isEqualTo(TRANSFERRED);
        assertThat(r.expiresAt()).isEqualTo(EXPIRES);

        assertThat(r.transferredFromUserId()).isEqualTo(TRANSFERRED_FROM);
        assertThat(r.transferredFromPhone()).isEqualTo("+263771234567");
    }

    @Test
    void theSenderAndTheIssuerAreNeverTheSameField() {
        // The whole point of carrying both: a cashier keying in a gift between
        // two customers is the ISSUER; the customer paying is the SENDER. A
        // console row that showed the till's number as "from" is the bug that
        // PR #129 removed from the write path — this stops it returning on the
        // read path as a mapping slip.
        Dtos.VoucherResponse r = VoucherService.toResponse(fullyPopulated());

        assertThat(r.senderPhone()).isNotEqualTo(r.issuerPhone());
        assertThat(r.senderPhone()).isEqualTo("+263782608767");
        assertThat(r.issuerPhone()).isEqualTo("+263772000111");
        assertThat(r.senderName()).isNotEqualTo(r.assigneeName());
    }

    @Test
    void bulkStockMapsItsNullsRatherThanInventingValues() {
        // Unassigned bulk stock: no holder, no sender. The row must carry nulls
        // so a client can tell "nobody" from "not loaded" — never "" or a
        // placeholder name.
        Voucher v = new Voucher();
        v.setId(ID);
        v.setCode("T6YB3ZPD9KMF");
        v.setStatus(Voucher.Status.ISSUED);
        v.setVoucherType(Voucher.VoucherType.SINGLE_USE);
        v.setMerchantId(MERCHANT);
        v.setBatchId(BATCH);
        v.setCampaignSource("spring-2026");
        v.setUsesRemaining(1);
        v.setValue(new BigDecimal("5.0000"));
        v.setCurrency("USD");
        v.setIssuedAt(ISSUED);

        Dtos.VoucherResponse r = VoucherService.toResponse(v);

        assertThat(r.assignedUserId()).isNull();
        assertThat(r.assigneePhone()).isNull();
        assertThat(r.assigneeName()).isNull();
        assertThat(r.senderName()).isNull();
        assertThat(r.senderPhone()).isNull();
        assertThat(r.deliveredAt()).as("bulk never attempts a dispatch").isNull();
        assertThat(r.viewedAt()).isNull();
        assertThat(r.redeemedAt()).isNull();
        assertThat(r.transferredAt()).isNull();
        assertThat(r.transferredFromUserId()).isNull();
        assertThat(r.transferredFromPhone()).isNull();
        // ...but the batch identity IS present, which is what groups the stock.
        assertThat(r.batchId()).isEqualTo(BATCH);
        assertThat(r.campaignSource()).isEqualTo("spring-2026");
    }

    @Test
    void withoutCode_dropsOnlyTheCode() {
        // The transfer path redacts the rotated code from the SENDER's view.
        // It rebuilds the record positionally, so it is the second place a
        // component swap can hide — assert it preserves everything else.
        Dtos.VoucherResponse full = VoucherService.toResponse(fullyPopulated());
        Dtos.VoucherResponse redacted = full.withoutCode();

        assertThat(redacted.code()).isNull();
        // Every other component identical: comparing the full records with the
        // code equalised catches a dropped or swapped field in one assertion,
        // and keeps working when a component is added later.
        assertThat(redacted).isEqualTo(new Dtos.VoucherResponse(
                full.id(), null, full.status(), full.voucherType(),
                full.merchantId(), full.shopId(), full.batchId(), full.campaignSource(),
                full.assignedUserId(), full.assigneePhone(), full.assigneeName(),
                full.senderName(), full.senderPhone(),
                full.issuerUserId(), full.issuerPhone(), full.issuerEmail(),
                full.usesRemaining(), full.value(), full.currency(), full.baseValue(),
                full.issuedAt(), full.deliveredAt(), full.viewedAt(),
                full.redeemedAt(), full.transferredAt(), full.expiresAt(),
                full.transferredFromUserId(), full.transferredFromPhone()));
    }

    @Test
    void issuedAtCarriesTheTimeOfDay_notJustTheDate() {
        // The console was rendering "17 Sept 2026" from a value that has always
        // been a full instant. Nothing in the mapper truncates it; this pins
        // that, so "we need the exact time" stays a client-side display fix.
        Voucher v = fullyPopulated();
        v.setIssuedAt(Instant.parse("2026-09-17T14:32:08Z"));

        assertThat(VoucherService.toResponse(v).issuedAt())
                .isEqualTo(Instant.parse("2026-09-17T14:32:08Z"));
    }
}
