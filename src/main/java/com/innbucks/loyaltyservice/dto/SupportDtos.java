package com.innbucks.loyaltyservice.dto;

import com.innbucks.loyaltyservice.entity.SupportMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request and response shapes of the customer-support surface
 * ({@code /loyalty/support/**}, V54).
 *
 * <p>Two rules run through every response here:
 * <ul>
 *   <li><b>Phones are masked</b> ({@code ****4567}) on every response, history
 *       included. The agent typed the number once, into the lookup body; nothing
 *       sends it back.</li>
 *   <li><b>No voucher code, ever.</b> A code is a bearer credential; a support
 *       agent who could read one could spend it. The resend action exists so a
 *       customer can get their code again without anyone else seeing it.</li>
 * </ul>
 */
public final class SupportDtos {

    private SupportDtos() {}

    // ---- Requests ----

    public record LookupRequest(
            @Schema(example = "+263771234567",
                    description = "The customer's phone, in any spelling the cell's country parses "
                            + "(0771234567, 263771234567, +263771234567). Sent in the BODY so it never "
                            + "lands in an access log or a URL.")
            @NotBlank @Size(max = 32) String phone) {}

    public record NoteRequest(
            @Schema(example = "Customer called about a voucher that did not arrive. Resent via WhatsApp.",
                    description = "1..2000 characters once HTML is stripped and whitespace trimmed.")
            @NotNull @Size(max = 8000) String body) {}

    public record MessageRequest(
            @Schema(example = "SMS_THEN_WHATSAPP",
                    description = "SMS, WHATSAPP, or SMS_THEN_WHATSAPP (WhatsApp only if the SMS fails).")
            @NotNull SupportMessage.Channel channel,
            @Schema(example = "Hi, your points adjustment has been applied. Check your balance in the app.",
                    description = "What the agent typed. HTML is stripped; the signature is appended on "
                            + "its own line. The recipient is NEVER part of the request — it is the "
                            + "looked-up customer's phone.")
            @NotBlank @Size(max = 2000) String body) {}

    public record ResendRequest(
            @Schema(example = "WHATSAPP", description = "SMS, WHATSAPP, or SMS_THEN_WHATSAPP.")
            @NotNull SupportMessage.Channel channel) {}

    public record SignOutRequest(
            @Schema(example = "Customer reports a lost phone.",
                    description = "Why. Recorded as an internal note on the customer, attributed to you.")
            @NotBlank @Size(max = 500) String reason) {}

    /**
     * {@code reason} is capped at 96 because it is stored where a merchant
     * adjustment stores it: {@code loyalty_transactions.reference}
     * (VARCHAR(100)) — and a later reversal writes {@code "REV-" + reference}
     * into the same column, so 96 is what keeps the adjustment reversible.
     * {@code SupportReasonWidthTest} ties the number to the entity.
     */
    public record AdjustRequest(
            @Schema(example = "8f14e45f-ceea-467a-9ba6-7c3f0e2a1b44",
                    description = "The loyalty account (membership) to adjust — must belong to the "
                            + "looked-up phone.")
            @NotNull UUID userId,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789",
                    description = "Merchant the adjustment is booked against — in the membership's program.")
            @NotNull UUID merchantId,
            @Schema(example = "250", description = "Whole points, positive credits, negative debits, never 0.")
            @NotNull @Digits(integer = 12, fraction = 0) BigDecimal points,
            @Schema(example = "Goodwill credit for failed voucher", description = "1..96 characters.")
            @NotBlank @Size(max = MAX_ADJUST_REASON) String reason) {}

    /** Ledger {@code points_ledger.reason} is VARCHAR(200) and receives {@code "reverse:" + reason}. */
    public record ReverseRequest(
            @Schema(example = "Duplicate earn on the same receipt", description = "1..192 characters.")
            @NotBlank @Size(max = MAX_REVERSE_REASON) String reason) {}

    public record UnblockRequest(
            @Schema(example = "Customer verified by phone; velocity block was a shared-device false positive.",
                    description = "Why. Recorded as an internal note on the customer, attributed to you.")
            @NotBlank @Size(max = 500) String reason) {}

    public static final int MAX_ADJUST_REASON = 96;
    public static final int MAX_REVERSE_REASON = 192;

    // ---- Shared pieces ----

    @Schema(description = "Who did it: the token's userUuid (else subject) and login.")
    public record AgentRef(
            @Schema(example = "5b0e7a1c-3f2d-4c9e-8a7b-6d5e4f3a2b1c") String uuid,
            @Schema(example = "agent.moyo@innbucks.co.zw") String login) {}

    // ---- Lookup + 360 ----

    public record LookupResponse(
            @Schema(description = "Carry this on every drill-down. Valid for you only, for "
                    + "loyalty.support.lookup-ttl (12h by default).")
            UUID lookupId,
            Instant expiresAt,
            Customer360 customer) {}

    public record Customer360(
            @Schema(example = "****4567") String phone,
            Registration registration,
            List<Membership> memberships,
            WalletSummary wallet,
            @Schema(description = "First page (10) of the cross-tenant statement.")
            PageResponse<TransactionLine> recentTransactions,
            VoucherSummary vouchers,
            OrderSummary voucherOrders,
            Sessions sessions,
            @Schema(nullable = true, description = "From user-service; null when it could not be read.")
            Tier tier,
            long notes,
            long messages) {}

    @Schema(description = "The phone-level registration fact (V40). registered = a live, unrevoked row.")
    public record Registration(boolean registered, String source, Instant registeredAt,
                               Instant revokedAt, String revokedReason) {}

    @Schema(description = "One per-tenant loyalty account (projection) of this phone.")
    public record Membership(UUID tenantId, String tenantName, UUID userId, String status,
                             String statusReason, Instant joinedAt) {}

    public record WalletSummary(BigDecimal totalBalance, List<WalletLine> wallets) {}

    public record WalletLine(UUID id, String label, String type, String pocket,
                             BigDecimal balance, LocalDate lockedUntil) {}

    public record TransactionLine(UUID id, UUID tenantId, UUID merchantId, String merchantName,
                                  UUID userId, String type, String status, BigDecimal amount,
                                  String currency, BigDecimal pointsDelta, String reference,
                                  UUID reversesId, UUID shopId, UUID postedBy, String channel,
                                  Instant createdAt) {}

    public record LedgerLine(UUID id, UUID walletId, UUID tenantId, UUID transactionId,
                             BigDecimal delta, BigDecimal balanceAfter, String reason,
                             Instant createdAt) {}

    @Schema(description = "A voucher WITHOUT its code. Every phone on it is masked.")
    public record VoucherLine(UUID id, UUID tenantId, UUID merchantId, String merchantName,
                              String status, String voucherType, BigDecimal value, String currency,
                              @Schema(example = "****4567") String holder, String holderName,
                              @Schema(example = "****8899") String sender, String senderName,
                              @Schema(example = "****1122") String transferredFrom,
                              int usesRemaining, Instant issuedAt, Instant deliveredAt,
                              Instant viewedAt, Instant redeemedAt, Instant expiresAt,
                              Instant transferredAt, String campaignSource) {}

    public record VoucherSummary(long heldLive, long heldTotal, long sent, long transferredAway,
                                 @Schema(description = "The 5 most recently issued vouchers this phone holds.")
                                 List<VoucherLine> recentHeld) {}

    @Schema(description = "A voucher purchase order the phone appears on. `roles` names which of "
            + "PAYER / RECIPIENT / SENDER it is.")
    public record OrderLine(UUID id, String orderRef, List<String> roles, UUID tenantId,
                            UUID merchantId, String merchantName, String status, BigDecimal amount,
                            String currency, String payer, String recipient, String sender,
                            String paidVia, Instant paidAt, Instant expiresAt, Instant createdAt,
                            UUID voucherId) {}

    public record OrderSummary(long total, List<OrderLine> recent) {}

    @Schema(description = "Refresh-token chains that could still renew a session (one per signed-in device).")
    public record Sessions(long activeChains) {}

    public record Tier(int currentTier, Integer nextTier) {}

    // ---- Notes ----

    public record NoteResponse(UUID id, String subjectKind,
                               @Schema(example = "****4567") String subjectId,
                               String body, AgentRef createdBy, Instant createdAt) {}

    // ---- Messages ----

    public record PreviewResponse(String channel, String recipientRole,
                                  @Schema(example = "****4567") String recipient,
                                  String text, int characters, int maxCharacters,
                                  @Schema(nullable = true, description = "Null for WHATSAPP.")
                                  Integer smsSegments,
                                  @Schema(description = "True when the SMS sanitiser changed the text.")
                                  boolean transliterated) {}

    public record MessageResponse(UUID id, String kind, String channelRequested, String deliveredVia,
                                  String outcome, String recipientRole,
                                  @Schema(example = "****4567") String recipient,
                                  @Schema(nullable = true, description = "Null for a secret-bearing kind "
                                          + "(VOUCHER_RESEND).")
                                  String text,
                                  AgentRef sentBy, Instant createdAt, Instant completedAt,
                                  String failureCode) {}

    /** {@code data} of a 429 {@code support_message_rate_limited}. */
    public record RateLimitDetail(String scope, int limit, int windowMinutes) {}

    // ---- Actions ----

    public record SignOutResponse(long chainsRevoked, int tokensRevoked, UUID noteId) {}

    public record UnblockResponse(Membership membership, UUID noteId) {}

    // ---- Oversight ----

    public record ActivityResponse(UUID id, AgentRef agent, String action, String subjectKind,
                                   @Schema(example = "****4567", description = "Masked when subjectKind is PHONE.")
                                   String subjectId,
                                   @Schema(description = "Ids, enums and amounts only.")
                                   Map<String, Object> detail,
                                   Instant createdAt) {}
}
