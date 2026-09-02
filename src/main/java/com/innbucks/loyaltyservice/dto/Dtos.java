package com.innbucks.loyaltyservice.dto;

import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Dtos {

    public record TenantRequest(
            @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                    description = "UUID of the user to attach as the tenant's first member. On registration " +
                                  "the tenant is created AND this user is added as a member in one call — no " +
                                  "separate join step is needed.")
            @NotNull UUID id,
            @Schema(example = "innbucks", description = "Short unique code for the tenant (URL-safe, no spaces).")
            @NotBlank String code,
            @Schema(example = "Innbucks Financial Services")
            @NotBlank @Size(max = 200) String name
    ) {}

    public record TenantResponse(UUID id, String code, String name, String status) {}

    public record TenantMemberResponse(UUID id, UUID tenantId, UUID userId, String email, Instant joinedAt) {}

    /**
     * Per-voucher fee configuration on the merchant. {@code type} selects
     * which of {@code fixed} / {@code percentage} applies (or both for
     * {@code FIXED_PLUS_PERCENTAGE}). {@code percentage} is expressed as a
     * whole-number percent — 2.5 means 2.5%.
     */
    public record FeeModel(
            @Schema(example = "FIXED_PLUS_PERCENTAGE", description = "How the per-voucher fee is computed.")
            Merchant.FeeType type,
            @Schema(example = "0.30", description = "Flat amount in the merchant's currency. Used when type is FIXED or FIXED_PLUS_PERCENTAGE.", nullable = true)
            BigDecimal fixed,
            @Schema(example = "2.5", description = "Whole-number percent applied to the voucher's face value. 2.5 means 2.5%. Used when type is PERCENTAGE or FIXED_PLUS_PERCENTAGE.", nullable = true)
            BigDecimal percentage
    ) {}

    /**
     * Optional at-onboarding override of the tenant's standard loyalty terms.
     * Supplying it creates the merchant's own {@code loyalty_rules} row in the
     * same call, so an operator never has to onboard the merchant and then
     * remember to POST a rule. Every field is optional — omit one and the
     * merchant keeps inheriting the tenant's global rule for it.
     */
    public record MerchantRuleOverride(
            @Schema(example = "PURCHASE", nullable = true,
                    description = "Transaction type the override applies to. Defaults to PURCHASE — the type "
                            + "the earning floor and the voucher fee schedules ride on.")
            TransactionType transactionType,
            @Schema(example = "2.000000", nullable = true,
                    description = "Points awarded per 1 unit of currency spent at this merchant. Defaults to 1.")
            BigDecimal pointsPerUnit,
            @Schema(example = "1.0000", nullable = true, description = "Multiplier applied on top of pointsPerUnit. Defaults to 1.")
            BigDecimal multiplier,
            @Schema(example = "500.0000", nullable = true, description = "Cap on points earnable in a single transaction. Null = uncapped.")
            BigDecimal maxPointsPerTxn,
            @Schema(example = "MAIN", nullable = true, description = "Target wallet pocket for earned points.")
            String pocket,
            @Schema(example = "5.00", nullable = true,
                    description = "Earning floor for this merchant: a transaction strictly below it earns ZERO "
                            + "points. Omit to inherit the tenant's global floor.")
            BigDecimal minTransactionAmount,
            @Schema(nullable = true,
                    description = "Voucher-issue fee for this merchant, overriding the tenant standard. "
                            + "Omit to inherit the global rule's fee.")
            FeeModel feeIssued,
            @Schema(nullable = true,
                    description = "Voucher-redeem fee for this merchant, overriding the tenant standard. "
                            + "Omit to inherit the global rule's fee.")
            FeeModel feeRedeemed
    ) {}

    public record MerchantRequest(
            @Schema(example = "Innbucks Westgate", description = "Display name of the merchant outlet (e.g. \"Chicken Inn Westgate\").")
            @NotBlank @Size(max = 200) String name,
            @Schema(example = "Coffee", nullable = true, description = "Business category (e.g. Coffee, Grocery, Fuel).")
            @Size(max = 100) String category,
            @Schema(example = "USD", nullable = true, description = "ISO 4217 currency code. Defaults to this cell's currency (ZW=USD, KE=KES) when omitted.")
            String currency,
            @Schema(example = "MONTHLY", allowableValues = {"DAILY", "WEEKLY", "MONTHLY"},
                    description = "Billing period the merchant is invoiced on. Defaults to MONTHLY when omitted. "
                            + "DAILY bills each completed day, WEEKLY the previous Mon–Sun week, MONTHLY the previous calendar month.")
            Merchant.BillingCycle billingCycle,
            @Schema(description = "Legacy per-merchant voucher-issue fee written straight onto the merchant record. "
                    + "Prefer loyaltyOverride.feeIssued, which puts the fee on the merchant's rule alongside the "
                    + "rest of its terms. Omit both to inherit the tenant standard.", nullable = true)
            FeeModel feeIssued,
            @Schema(description = "Legacy per-merchant voucher-redeem fee written straight onto the merchant record. "
                    + "Prefer loyaltyOverride.feeRedeemed.", nullable = true)
            FeeModel feeRedeemed,
            @Schema(nullable = true,
                    description = "Override the tenant's standard loyalty terms for this merchant at onboarding. "
                            + "Creates the merchant's own rule in the same call. Omit it entirely and the merchant "
                            + "inherits every global rule as-is.")
            MerchantRuleOverride loyaltyOverride,
            @Schema(example = "false", nullable = true,
                    description = "Deliberately onboard this merchant with NO voucher-issue fee. Creation is "
                            + "REFUSED with MERCHANT_ZERO_ISSUE_FEE when the effective ISSUE fee works out to zero "
                            + "and this is not true, so an unbilled merchant is always someone's decision rather "
                            + "than a forgotten field. The REDEEM fee may be zero freely — only issuing is guarded. "
                            + "Requires waiveFeesReason.")
            Boolean waiveFees,
            @Schema(example = "Pilot partner - free for the first quarter, revisit 2026-10", nullable = true,
                    description = "Why billing was waived. Required when waiveFees is true; it is what makes the "
                            + "zero-fee audit readable months later.")
            @Size(max = 200) String waiveFeesReason
    ) {
        /** Back-compat constructor for the pre-override shape. */
        public MerchantRequest(String name, String category, String currency,
                               Merchant.BillingCycle billingCycle,
                               FeeModel feeIssued, FeeModel feeRedeemed) {
            this(name, category, currency, billingCycle, feeIssued, feeRedeemed, null, null, null);
        }

        /** Back-compat constructor for the pre-waiver shape. */
        public MerchantRequest(String name, String category, String currency,
                               Merchant.BillingCycle billingCycle,
                               FeeModel feeIssued, FeeModel feeRedeemed,
                               MerchantRuleOverride loyaltyOverride) {
            this(name, category, currency, billingCycle, feeIssued, feeRedeemed, loyaltyOverride, null, null);
        }
    }

    public record MerchantResponse(UUID id, UUID tenantId, String name, String category,
                                   String currency, Merchant.BillingCycle billingCycle,
                                   Merchant.Status status,
                                   FeeModel feeIssued, FeeModel feeRedeemed,
                                   @Schema(nullable = true,
                                           description = "Id of the merchant-specific rule created from loyaltyOverride "
                                                   + "at onboarding. Null when the merchant was onboarded without an "
                                                   + "override; not populated on list/get — read GET /loyalty/rules for "
                                                   + "the merchant's current rules.")
                                   UUID loyaltyRuleId,
                                   @Schema(example = "false",
                                           description = "True when this merchant was deliberately onboarded with no "
                                                   + "billing. See the zero-fee audit at GET /loyalty/merchants/fee-audit.")
                                   boolean feeWaived,
                                   @Schema(nullable = true, description = "Why billing was waived, when it was.")
                                   String feeWaivedReason) {}

    /** One row of the zero-fee audit: a merchant we issue vouchers for free today. */
    public record ZeroFeeMerchant(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789") UUID merchantId,
            @Schema(example = "Innbucks Westgate") String name,
            @Schema(example = "ACTIVE") String status,
            @Schema(example = "false",
                    description = "False means nobody chose this — it is a merchant we onboarded and forgot to price.")
            boolean waived,
            @Schema(nullable = true, example = "Pilot partner - free for the first quarter") String waivedReason,
            @Schema(example = "true", description = "Whether redemption is also free. Reported, never refused.")
            boolean redeemsForFree) {}

    /** The zero-fee audit: every merchant issuing for free, and how many were deliberate. */
    public record ZeroFeeAudit(
            @Schema(example = "12", description = "Merchants examined.") int merchantsExamined,
            @Schema(example = "3", description = "Issuing vouchers for free today.") int issuingForFree,
            @Schema(example = "1", description = "Of those, deliberately waived.") int waived,
            @Schema(example = "2", description = "Of those, unexplained — the ones to price.") int unwaived,
            List<ZeroFeeMerchant> merchants) {}

    // A Shop is a physical outlet under a Merchant. e.g. "Pizza Inn Avondale"
    // and "Pizza Inn Westgate" are two shops under the "Pizza Inn" merchant.
    // Shops inherit their merchant's rules; if the merchant has none, they
    // fall back to global tenant-wide rules (handled transparently by
    // RulesEngine when transactions reference the shop's merchantId).
    public record ShopRequest(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789",
                    description = "Merchant this shop belongs to.")
            @NotNull UUID merchantId,
            @Schema(example = "Pizza Inn Avondale", description = "Display name of the shop outlet.")
            @NotBlank @Size(max = 200) String name,
            @Schema(example = "123 King George Rd, Avondale, Harare", nullable = true)
            @Size(max = 500) String address
    ) {}

    public record ShopResponse(UUID id, UUID tenantId, UUID merchantId, String name,
                               String address,
                               com.innbucks.loyaltyservice.entity.Shop.Status status,
                               Instant createdAt) {}

    // CSV bulk-upload result. Each row gets its own DB transaction, so a
    // bad row in the middle of a 100-row file doesn't block the rest —
    // the FE can show "82 created, 18 failed" and the failure list lets
    // the operator fix the bad rows and re-upload just those.
    public record BulkShopUploadResult(
            @Schema(example = "100", description = "Total data rows attempted (excludes the header).")
            int processed,
            @Schema(example = "82", description = "Rows that created a shop successfully.")
            int created,
            @Schema(example = "18", description = "Rows that failed validation or persistence.")
            int failed,
            @ArraySchema(
                    arraySchema = @Schema(description = "Per-row failure detail. Empty on a fully clean upload."),
                    schema = @Schema(implementation = BulkShopRowFailure.class))
            List<BulkShopRowFailure> failures
    ) {}

    public record BulkShopRowFailure(
            @Schema(example = "5", description = "1-based row number in the uploaded CSV (header is row 1; first data row is 2).")
            int row,
            @Schema(example = "Pizza Inn Belgravia", nullable = true,
                    description = "The `name` value from the row, if it was parseable.")
            String name,
            @Schema(example = "name is required", description = "Human-readable reason the row was rejected.")
            String error
    ) {}

    // Guest (unregistered-customer) shop checkout. The shop/merchant is the
    // authenticated caller; the customer is identified by phoneNumber alone — no
    // account required. Cash-only EARN: a guest can RECEIVE points but cannot
    // REDEEM until they register (loyalty auto-creates a PENDING wallet keyed to
    // the phone, promoted to spendable on registration). The merchant is derived
    // from the shop, so it is NOT part of the request — the controller enforces
    // ownership from the caller's JWT merchant scope instead.
    public record GuestShopCheckoutRequest(
            @Schema(example = "+263771234567",
                    description = "Guest customer's phone number (E.164). Points accrue against this phone; " +
                                  "no registration required to earn.")
            @NotBlank String phoneNumber,
            @Schema(example = "10.00",
                    description = "Cash the customer paid. Points are earned on this per the merchant's " +
                                  "loyalty rules. No points are redeemed — a guest can't spend.")
            @NotNull @Positive BigDecimal cashAmount
    ) {}

    public record GuestShopCheckoutResponse(
            @Schema(example = "11111111-aaaa-bbbb-cccc-222222222222")
            UUID shopId,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789")
            UUID merchantId,
            @Schema(example = "99999999-8888-7777-6666-555555555555",
                    description = "Loyalty user the points accrued to. PENDING (receive-only) until the phone registers.")
            UUID loyaltyUserId,
            @Schema(example = "10.00", description = "Cash amount the points were earned on.")
            BigDecimal cashAmount,
            @Schema(example = "10.0000", description = "Points awarded for this checkout.")
            BigDecimal pointsEarned,
            @Schema(example = "10.0000", description = "Customer's wallet balance after the earn.")
            BigDecimal walletBalanceAfter,
            @Schema(example = "7a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9", description = "Ledger transaction id for the earn.")
            UUID purchaseTransactionId
    ) {}

    // Loyalty enrolment is by phone number only — name/email/nationalId belong
    // to user-service. Loyalty validates the phone exists there before
    // creating its local LoyaltyUser projection.
    public record UserEnrolRequest(
            @Schema(example = "+263771234567", description = "Customer's phone number (E.164 format). Must exist in user-service.")
            @NotBlank String phoneNumber,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "Merchant this user is attached to (e.g. a cashier). Null for plain customer enrolment.")
            UUID merchantId,
            @Schema(example = "END_USER", allowableValues = {"END_USER", "MERCHANT_ADMIN", "MERCHANT_FINANCE", "TENANT_ADMIN", "PLATFORM_ADMIN", "AUDITOR"})
            LoyaltyUser.Role role
    ) {}

    public record UserResponse(UUID id, UUID tenantId, String phoneNumber,
                               String role, String status) {}

    public record WalletResponse(UUID id, UUID userId, String label, String type,
                                 String pocket, BigDecimal balance, LocalDate lockedUntil) {}

    public record SubWalletRequest(
            @Schema(example = "Holiday Savings", description = "Human-readable wallet label.")
            @NotBlank String label,
            @Schema(example = "SAVINGS", nullable = true, description = "Named pocket within the wallet for rule targeting.")
            String pocket,
            @Schema(example = "LOCKED", nullable = true, allowableValues = {"STANDARD", "LOCKED"},
                    description = "LOCKED wallets cannot be spent until lockedUntil.")
            String type,
            @Schema(example = "2025-12-31", nullable = true,
                    description = "Date until which the wallet is locked (LOCKED type only). ISO-8601 date.")
            LocalDate lockedUntil
    ) {}

    // merchantId is taken from the JWT for SHOP_ADMIN (who carry the claim) and from
    // the request body for MERCHANT_ADMIN (who do not). When both are absent the rule
    // is created as a tenant-wide global baseline. CallerDetails.resolveMerchantId
    // centralises the source-of-truth selection.
    public record RuleRequest(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "Merchant the rule applies to. Required for MERCHANT_ADMIN callers; " +
                                  "ignored when the JWT carries a merchantId (SHOP_ADMIN). Null/omitted " +
                                  "by TENANT_ADMIN+ creates a tenant-wide global rule.")
            UUID merchantId,
            @Schema(example = "PURCHASE")
            @NotNull TransactionType transactionType,
            @Schema(example = "1.000000", description = "Points awarded per 1 unit of currency spent. Must be positive "
                    + "and within the platform ceiling (loyalty.earn-rate.max-points-per-unit).")
            @NotNull @Positive BigDecimal pointsPerUnit,
            @Schema(example = "2.0000", nullable = true, description = "Multiplier applied on top of pointsPerUnit (e.g. 2x "
                    + "during a promo). When present must be positive and within the platform ceiling.")
            @Positive BigDecimal multiplier,
            @Schema(example = "500.0000", nullable = true, description = "Cap on points earnable in a single transaction.")
            @Positive BigDecimal maxPointsPerTxn,
            @Schema(example = "MAIN", nullable = true, description = "Target wallet pocket for earned points.")
            String pocket,
            @Schema(example = "2026-06-01T00:00:00Z", nullable = true, description = "When this rule becomes active (null = immediately).")
            Instant startsAt,
            @Schema(example = "2026-12-31T23:59:59Z", nullable = true, description = "When this rule expires (null = no expiry).")
            Instant endsAt,
            @Schema(example = "5.00", nullable = true,
                    description = "Earning floor: a transaction amount strictly below this earns ZERO points. " +
                                  "Null on a merchant rule inherits the global rule's floor; null everywhere = no floor.")
            BigDecimal minTransactionAmount,
            @Schema(nullable = true,
                    description = "Voucher-issue fee schedule at rule level. On a GLOBAL rule this is the tenant " +
                                  "STANDARD every merchant inherits; on a merchant rule it overrides the standard " +
                                  "for that merchant. Null = not configured at this level (inherit).")
            FeeModel feeIssued,
            @Schema(nullable = true,
                    description = "Voucher-redeem fee schedule at rule level — same inheritance as feeIssued.")
            FeeModel feeRedeemed
    ) {
        /** Back-compat constructor for the pre-V29 shape (no floor, no rule-level fees). */
        public RuleRequest(UUID merchantId, TransactionType transactionType, BigDecimal pointsPerUnit,
                           BigDecimal multiplier, BigDecimal maxPointsPerTxn, String pocket,
                           Instant startsAt, Instant endsAt) {
            this(merchantId, transactionType, pointsPerUnit, multiplier, maxPointsPerTxn,
                    pocket, startsAt, endsAt, null, null, null);
        }
    }

    // merchantId follows the same rules as RuleRequest.
    public record CampaignRequest(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "Merchant the campaign applies to. See RuleRequest.merchantId for source selection.")
            UUID merchantId,
            @Schema(example = "Weekend 2x Points")
            @NotBlank @Size(max = 200) String name,
            @Schema(example = "2.0000", description = "Points multiplier during the campaign window. Must be positive "
                    + "and within the platform ceiling (loyalty.earn-rate.max-multiplier).")
            @NotNull @Positive BigDecimal multiplier,
            @Schema(example = "PURCHASE", nullable = true)
            TransactionType transactionType,
            @Schema(example = "2026-06-04T00:00:00Z")
            @NotNull Instant startsAt,
            @Schema(example = "2026-06-08T23:59:59Z")
            @NotNull Instant endsAt
    ) {}

    // merchantId from JWT (SHOP_ADMIN) or request body (MERCHANT_ADMIN); see CallerDetails.resolveMerchantId.
    // Recipient is identified by EITHER userId (registered customer) or assigneePhone
    // (unregistered — a PENDING LoyaltyUser is auto-created so points can accrue). Exactly one is required.
    public record TransactionRequest(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "Merchant the transaction posts against. Required when the caller's JWT " +
                                  "carries no merchantId claim (MERCHANT_ADMIN). Ignored otherwise.")
            UUID merchantId,
            @Schema(example = "11111111-2222-3333-4444-555555555555", nullable = true,
                    description = "Loyalty user ID of the recipient. Mutually exclusive with assigneePhone; exactly one must be set.")
            UUID userId,
            @Schema(example = "+263771234567", nullable = true,
                    description = "Phone number of the recipient. If no LoyaltyUser exists yet, one is auto-created in PENDING status so points accrue against the phone until the customer registers.")
            String assigneePhone,
            @Schema(example = "PURCHASE", allowableValues = {"PURCHASE", "BILL_PAYMENT", "QR_PAY", "WALLET_TOPUP", "POINTS_PURCHASE",
                    "PROMO", "REFUND", "TRANSFER", "REDEMPTION", "ADJUSTMENT", "CARD_PAYMENT"})
            @NotNull TransactionType type,
            @Schema(example = "100.00", nullable = true,
                    description = "Transaction amount in the merchant's currency. Must be zero or positive " +
                                  "when supplied (a negative amount would mint points via a later reversal — OWASP A04). " +
                                  "Optional: omit it for types that don't accrue on a cash amount.")
            @PositiveOrZero(message = "amount must be zero or positive")
            BigDecimal amount,
            @Schema(example = "USD", nullable = true, description = "ISO 4217 currency code; defaults to the merchant's currency when omitted.")
            String currency,
            @Schema(example = "POS-20260504-0001", nullable = true,
                    description = "The till's receipt reference. REQUIRED for staff-posted PURCHASE / "
                                  + "CARD_PAYMENT earns (400 REFERENCE_REQUIRED without it) so every sale "
                                  + "earn reconciles against a receipt; optional for other types and for "
                                  + "the QR flow. Unique per merchant — a duplicate replays as 409 "
                                  + "DUPLICATE_REFERENCE rather than double-earning.")
            String reference
    ) {}

    public record TransactionResponse(UUID id, TransactionType type, BigDecimal amount,
                                      BigDecimal pointsDelta, BigDecimal balanceAfter,
                                      UUID ruleId, UUID campaignId, UUID shopId,
                                      // Attribution (additive, V32): which staff/customer account
                                      // created the row (null = server-side or legacy), and which
                                      // channel an EARN arrived through (TYPED_PHONE / QR_PRESENCE /
                                      // CHECKOUT_S2S; null for non-earn and legacy rows).
                                      UUID postedBy, com.innbucks.loyaltyservice.entity.EarnChannel channel,
                                      String reference, Instant createdAt,
                                      // Billing back-reference (additive, V33 / IN-9): the invoice
                                      // whose period covered this row. Null = not billed — either the
                                      // period isn't invoiced yet, or the merchant had no billable
                                      // voucher activity so no invoice was raised at all.
                                      UUID invoiceId,
                                      // Multi-currency (additive, V37). `currency` is the currency of
                                      // `amount` — always render the two together, never `amount`
                                      // alone. `baseAmount` is the USD value points were awarded on,
                                      // frozen at the rate in force when the row was written; null
                                      // means not known in USD (a pre-V37 non-USD row), never zero.
                                      String currency, BigDecimal baseAmount) {}

    // Sender (fromUserId) MUST be a registered LoyaltyUser — you can't spend a
    // pending balance. Recipient may be either a registered user (toUserId) or
    // an unregistered phone (toPhone); exactly one must be set.
    public record TransferRequest(
            @Schema(example = "11111111-2222-3333-4444-555555555555", description = "Sender's loyalty user ID.")
            @NotNull UUID fromUserId,
            @Schema(example = "66666666-7777-8888-9999-000000000000", nullable = true,
                    description = "Recipient's loyalty user ID. Mutually exclusive with toPhone.")
            UUID toUserId,
            @Schema(example = "+263771234567", nullable = true,
                    description = "Recipient's phone number. If no LoyaltyUser exists, a PENDING one is created — the gift becomes spendable once they register.")
            String toPhone,
            @Schema(example = "250.0000", description = "Points to transfer.")
            @Positive BigDecimal points,
            @Schema(example = "Birthday gift", nullable = true)
            @Size(max = 1000) String reason
    ) {}

    // merchantId from JWT (SHOP_ADMIN) or request body (MERCHANT_ADMIN); see CallerDetails.resolveMerchantId.
    public record RedemptionRequest(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "Merchant performing the redemption. Required for MERCHANT_ADMIN; ignored " +
                                  "when JWT carries merchantId.")
            UUID merchantId,
            @Schema(example = "11111111-2222-3333-4444-555555555555")
            @NotNull UUID userId,
            @Schema(example = "500.0000", nullable = true,
                    description = "Points to redeem. Provide EITHER this or `amount` (the currency value " +
                                  "to cover). When `amount` is given the server computes the points from the " +
                                  "platform redemption rate and this field is optional; if you send both, they " +
                                  "must agree at the current rate or the call is rejected (RATE_MISMATCH). " +
                                  "Prefer `amount` — it lets the platform, not the caller, decide a point's value.")
            @Positive BigDecimal points,
            @Schema(example = "Counter redemption by cashier", nullable = true)
            @Size(max = 1000) String reason,
            @Schema(example = "a3b9c1d2-1234-5678-9abc-def012345678", nullable = true,
                    description = "Idempotency key — a stable, caller-supplied reference for this logical " +
                                  "redemption (e.g. the booking id). A repeat redeem with the same " +
                                  "(merchant, reference) replays the original instead of debiting the wallet " +
                                  "again, so a retry can't double-spend. Omit for one-off redemptions.")
            String reference,
            @Schema(example = "5.0000", nullable = true,
                    description = "Currency value to redeem (e.g. $5.00 off), expressed in `currency`. When " +
                                  "present the server converts it to USD, then to a whole-points debit at the " +
                                  "platform redemption rate — the correct direction for the model, since the " +
                                  "platform sets what a point is worth. Provide EITHER this or `points`.")
            @Positive BigDecimal amount,
            @Schema(example = "ZWG", nullable = true,
                    description = "ISO 4217 currency of `amount`, and the currency the redemption's value is " +
                                  "recorded in. Defaults to the merchant's currency when omitted. Must be in " +
                                  "the cell's supported set, and a non-USD currency needs an in-force exchange " +
                                  "rate (NO_FX_RATE otherwise).")
            String currency
    ) {
        /**
         * Back-compat constructor for callers built against the pre-rate,
         * points-only shape. New callers should prefer the full constructor and
         * pass {@code amount} to redeem by currency value.
         */
        public RedemptionRequest(UUID merchantId, UUID userId, BigDecimal points,
                                 String reason, String reference) {
            this(merchantId, userId, points, reason, reference, null, null);
        }

        /**
         * Back-compat constructor for callers built against the pre-multi-currency
         * shape (no {@code currency}). Omitting it means "the merchant's
         * currency", which is what those callers always meant.
         */
        public RedemptionRequest(UUID merchantId, UUID userId, BigDecimal points,
                                 String reason, String reference, BigDecimal amount) {
            this(merchantId, userId, points, reason, reference, amount, null);
        }
    }

    /** Set a new platform redemption rate (SUPER_ADMIN only). Append-only + effective-dated. */
    public record RedemptionRateRequest(
            @Schema(example = "100.0000", description = "Points required to redeem one unit of currency. " +
                    "100 => 100 points buys $1 of value. Must be greater than zero.")
            @NotNull @Positive BigDecimal pointsPerUnit,
            @Schema(example = "USD", nullable = true, description = "ISO 4217 currency; defaults to USD.")
            String currency,
            @Schema(example = "2026-10-01T00:00:00Z", nullable = true,
                    description = "When the rate takes force (UTC). Omit for immediate; a future instant " +
                                  "schedules the change and the resolver ignores it until then.")
            Instant effectiveFrom,
            @Schema(example = "Launch rate", nullable = true, description = "Why this rate was set — shown in history.")
            @Size(max = 500) String note
    ) {}

    /** A single effective-dated redemption rate row. */
    public record RedemptionRateResponse(
            @Schema(example = "00000000-0000-0000-0000-000000000001") UUID id,
            @Schema(example = "100.0000") BigDecimal pointsPerUnit,
            @Schema(example = "USD") String currency,
            @Schema(example = "2026-10-01T00:00:00Z") Instant effectiveFrom,
            @Schema(example = "11111111-2222-3333-4444-555555555555", nullable = true,
                    description = "Operator who set it; null for the seeded platform default.")
            UUID createdBy,
            @Schema(example = "Launch rate", nullable = true) String note,
            @Schema(example = "2026-09-01T14:30:00Z") Instant createdAt
    ) {
        public static RedemptionRateResponse of(com.innbucks.loyaltyservice.entity.RedemptionRate r) {
            return new RedemptionRateResponse(r.getId(), r.getPointsPerUnit(), r.getCurrency(),
                    r.getEffectiveFrom(), r.getCreatedBy(), r.getNote(), r.getCreatedAt());
        }
    }

    public record ExchangeRateRequest(
            @Schema(example = "ZWG", description = "Quote currency (ISO 4217). Must be in the cell's " +
                    "supported set and must NOT be the base (USD is 1 by definition).")
            @NotBlank String currency,
            @Schema(example = "26.700000", description = "Units of the quote currency per 1 USD. " +
                    "Must be greater than zero. A change beyond the sanity band vs the current rate is " +
                    "refused unless force=true with a note.")
            @NotNull @Positive BigDecimal ratePerUsd,
            @Schema(example = "2026-10-01T00:00:00Z", nullable = true,
                    description = "When the rate takes force (UTC). Omit for immediate; a future instant " +
                                  "schedules the change and the resolver ignores it until then.")
            Instant effectiveFrom,
            @Schema(example = "RBZ interbank 2026-09-02", nullable = true,
                    description = "Why this rate was set — shown in history. REQUIRED when force=true.")
            @Size(max = 500) String note,
            @Schema(example = "false", nullable = true,
                    description = "Set true (with a note) to push a rate through the sanity band deliberately.")
            Boolean force
    ) {}

    /** A single effective-dated FX rate row (USD base). */
    public record ExchangeRateResponse(
            @Schema(example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") UUID id,
            @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", nullable = true,
                    description = "Null = a platform row (the inherited \"bank rate\" default); set = " +
                                  "that tenant's own override, which beats the platform rows for that " +
                                  "tenant.")
            UUID tenantId,
            @Schema(example = "ZWG") String currency,
            @Schema(example = "26.700000", nullable = true,
                    description = "Units of the quote currency per 1 USD. Null on a `cleared` row — "
                                  + "a revocation carries no rate.")
            BigDecimal ratePerUsd,
            @Schema(example = "false",
                    description = "True = this row REVOKED its scope's override rather than setting a "
                                  + "rate, so the scope falls back to the platform rate from "
                                  + "`effectiveFrom`. Only ever true on a tenant-scoped row.")
            boolean cleared,
            @Schema(example = "2026-10-01T00:00:00Z") Instant effectiveFrom,
            @Schema(example = "ADMIN", allowableValues = {"ADMIN", "FEED"},
                    description = "ADMIN = operator-entered (e.g. the daily RBZ figure); FEED = the " +
                                  "scheduled public-feed job (later phase).")
            String source,
            @Schema(example = "11111111-2222-3333-4444-555555555555", nullable = true,
                    description = "Operator who set it; null for FEED rows.")
            UUID createdBy,
            @Schema(example = "RBZ interbank 2026-09-02", nullable = true) String note,
            @Schema(example = "2026-09-02T08:30:00Z") Instant createdAt
    ) {
        public static ExchangeRateResponse of(com.innbucks.loyaltyservice.entity.ExchangeRate r) {
            return new ExchangeRateResponse(r.getId(), r.getTenantId(), r.getCurrency(), r.getRatePerUsd(),
                    r.isCleared(),
                    r.getEffectiveFrom(), r.getSource() == null ? null : r.getSource().name(),
                    r.getCreatedBy(), r.getNote(), r.getCreatedAt());
        }
    }

    // merchantId from JWT (SHOP_ADMIN) or request body (MERCHANT_ADMIN); null means tenant-wide template.
    public record VoucherTemplateRequest(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "Merchant the template belongs to. Required for MERCHANT_ADMIN unless " +
                                  "creating a tenant-wide template. Ignored when JWT carries merchantId.")
            UUID merchantId,
            @Schema(example = "$5 Off Your Next Coffee")
            @NotBlank @Size(max = 200) String name,
            @Schema(example = "SINGLE_USE",
                    allowableValues = {"SINGLE_USE", "MULTI_USE", "CAMPAIGN", "REFERRAL", "CORPORATE"})
            @NotNull VoucherTemplate.VoucherType type,
            @Schema(example = "AMOUNT", allowableValues = {"AMOUNT", "PERCENT", "FREE_ITEM", "COMBO"},
                    description = "Shape of the discount the template represents. The numeric value " +
                                  "(e.g. $5, 10%) is supplied per issuance in IssueVoucherRequest.value.")
            @NotNull VoucherTemplate.ValueType valueType,
            @Schema(example = "USD", nullable = true, description = "ISO 4217 currency code; defaults to the merchant's currency when omitted.")
            String currency,
            @Schema(example = "COFFEE-001", nullable = true, description = "SKU of the free item (FREE_ITEM type only).")
            String freeItemSku,
            @Schema(example = "1", description = "How many times this voucher can be used before it expires.")
            @Min(1) int usageLimit,
            @Schema(example = "30", nullable = true, description = "Days from issue until the voucher expires.")
            Integer validityDays,
            @ArraySchema(
                    arraySchema = @Schema(
                            nullable = true,
                            description = "Shop IDs where this voucher can be redeemed. Null or empty = every " +
                                          "shop under the merchant (or every shop in the tenant for tenant-wide " +
                                          "templates)."),
                    schema = @Schema(type = "string", format = "uuid",
                            example = "11111111-aaaa-bbbb-cccc-222222222222"))
            List<UUID> applicableOutlets
    ) {}

    public record IssueVoucherRequest(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "Issuing merchant. Required for MERCHANT_ADMIN; ignored when JWT carries merchantId.")
            UUID merchantId,
            @Schema(example = "d6e2f4a5-4567-8901-bcde-f01234567890", description = "Template to issue from.")
            @NotNull UUID templateId,
            @Schema(example = "5.0000", nullable = true,
                    description = "Per-issuance face value (e.g. 5 for $5 off, 10 for 10% off). Required for " +
                                  "AMOUNT and PERCENT value-types; ignored for FREE_ITEM / COMBO. The " +
                                  "value is snapshotted onto the issued voucher and cannot be changed.")
            BigDecimal value,
            @Schema(example = "+263771234567", nullable = true, description = "Recipient phone — used if assignedUserId is null.")
            String assigneePhone,
            @Schema(example = "Alice Moyo", nullable = true)
            @Size(max = 200) String assigneeName,
            @Schema(example = "11111111-2222-3333-4444-555555555555", nullable = true,
                    description = "Loyalty user ID of the recipient. Takes priority over assigneePhone.")
            UUID assignedUserId,
            @Schema(example = "SMS", nullable = true, allowableValues = {"SMS", "WHATSAPP", "EMAIL", "PUSH", "POS", "NONE"})
            Voucher.DeliveryChannel deliveryChannel,
            @Schema(example = "WINTER_PROMO_2026", nullable = true, description = "Campaign tag for reporting.")
            String campaignSource,
            @Schema(example = "3", nullable = true, description = "Override the template's usageLimit for this issuance only.")
            Integer usesOverride,
            @Schema(example = "14", nullable = true, description = "Override the template's validityDays for this issuance only.")
            Integer validityDaysOverride
    ) {}

    public record BulkIssueRequest(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "Issuing merchant. Required for MERCHANT_ADMIN; ignored when JWT carries merchantId.")
            UUID merchantId,
            @Schema(example = "d6e2f4a5-4567-8901-bcde-f01234567890")
            @NotNull UUID templateId,
            @Schema(example = "5.0000", nullable = true,
                    description = "Per-voucher face value applied to every voucher in the batch. Required " +
                                  "for AMOUNT and PERCENT value-types; ignored for FREE_ITEM / COMBO.")
            BigDecimal value,
            @Schema(example = "100", description = "Number of vouchers to generate.")
            @Min(1) int quantity,
            @Schema(example = "WINTER_PROMO_2026", nullable = true)
            String campaign,
            @Schema(example = "NONE", nullable = true, allowableValues = {"SMS", "WHATSAPP", "EMAIL", "PUSH", "POS", "NONE"})
            Voucher.DeliveryChannel deliveryChannel
    ) {}

    public record VoucherResponse(UUID id, String code, String status,
                                  UUID templateId, UUID assignedUserId,
                                  String assigneePhone, int usesRemaining,
                                  // value snapshot — copied from the template at issuance time and frozen.
                                  // valueType={AMOUNT, PERCENT, FREE_ITEM, COMBO} tells the client how to
                                  // render `value` (currency-formatted amount, percent off, etc.).
                                  String valueType, BigDecimal value, String currency,
                                  Instant issuedAt, Instant expiresAt,
                                  // Multi-currency liability (additive, V38): the USD worth of `value`,
                                  // frozen at the rate in force when the voucher was ISSUED. Null when
                                  // there is no money figure to convert — a PERCENT/FREE_ITEM/COMBO
                                  // voucher, or a pre-V38 row — never zero. Display uses `value` +
                                  // `currency`; this is for liability reporting, not the customer.
                                  BigDecimal baseValue) {}

    /**
     * Hand a voucher to another customer. SINGLE HOP — see
     * {@code VoucherService.transfer}: the recipient becomes the new holder and
     * cannot pass it on again.
     *
     * <p>Recipient is either a registered {@code toUserId} or a {@code toPhone}
     * (auto-enrolled PENDING if unknown) — exactly one, same rule as the points
     * {@link TransferRequest}. The sender is not a field: it is the voucher's
     * current assignee, and the caller must be them.
     */
    public record VoucherTransferRequest(
            @Schema(example = "66666666-7777-8888-9999-000000000000", nullable = true,
                    description = "Recipient's loyalty user ID. Mutually exclusive with toPhone.")
            UUID toUserId,
            @Schema(example = "+263771234567", nullable = true,
                    description = "Recipient's phone number. If no LoyaltyUser exists, a PENDING one is "
                                + "created — the voucher becomes redeemable once they register.")
            String toPhone,
            @Schema(example = "Passing this on to my sister", nullable = true,
                    description = "Optional note, recorded as the recipient's assignee name context.")
            @Size(max = 200) String note
    ) {}

    // merchantId from JWT (SHOP_ADMIN) or request body (MERCHANT_ADMIN); see CallerDetails.resolveMerchantId.
    public record RedeemVoucherRequest(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "Merchant performing the redemption. Required for MERCHANT_ADMIN.")
            UUID merchantId,
            @Schema(example = "VCH-AB12CD34", description = "Voucher redemption code from the customer.")
            @NotBlank String code,
            @Schema(example = "11111111-2222-3333-4444-555555555555", nullable = true)
            UUID userId,
            @Schema(example = "WESTGATE", nullable = true, description = "Outlet code within the merchant.")
            String outletCode,
            @Schema(example = "abc123def456", nullable = true, description = "Device fingerprint for fraud detection.")
            String deviceFingerprint,
            @Schema(example = "192.168.1.100", nullable = true)
            String ipAddress
    ) {}

    public record RedemptionResponse(UUID redemptionId, UUID voucherId, String status,
                                     int usesRemaining, BigDecimal value, String valueType,
                                     Instant redeemedAt) {}

    public record QrIssueRequest(
            @Schema(example = "MERCHANT", allowableValues = {"MERCHANT", "USER"})
            @NotNull com.innbucks.loyaltyservice.entity.QrToken.SourceType sourceType,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", description = "ID of the merchant or user generating the QR.")
            @NotNull UUID sourceId,
            @Schema(example = "QR_PAY", allowableValues = {"QR_PAY", "PURCHASE"})
            @NotNull TransactionType transactionType,
            @Schema(example = "50.00", nullable = true, description = "Pre-encoded amount (optional — for fixed-amount QRs).")
            BigDecimal amount,
            @Schema(example = "USD", nullable = true, description = "ISO 4217 currency code; defaults to the merchant's currency when omitted.")
            String currency,
            @Schema(example = "300", nullable = true, description = "Token TTL in seconds. Defaults to 300 (5 minutes).")
            Integer ttlSeconds
    ) {}

    public record QrPayload(String token, String signature, String tenantId,
                            String sourceType, String sourceId, String transactionType,
                            Instant expiresAt) {}

    public record QrConsumeRequest(
            @Schema(example = "qr_2026_e8f7c4d2a1b3", description = "Token from the scanned QR payload.")
            @NotBlank String token,
            @Schema(example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
                    description = "HMAC-SHA256 signature from the QR payload.")
            @NotBlank String signature,
            @Schema(example = "11111111-2222-3333-4444-555555555555", description = "Loyalty user ID of the customer scanning the QR.")
            @NotNull UUID userId,
            @Schema(example = "POS-20260504-0042", nullable = true, description = "Merchant's external reference for this transaction.")
            String reference
    ) {}

    public record InvoiceResponse(UUID id, String invoiceNumber, UUID merchantId,
                                  LocalDate periodStart, LocalDate periodEnd,
                                  BigDecimal pointsIssued, BigDecimal pointsRedeemed,
                                  long vouchersIssued, long vouchersRedeemed,
                                  BigDecimal totalAmount, String currency, String status,
                                  Instant paidAt) {}

    public record OperatorDashboard(long totalTenants, long activeMerchants,
                                    long transactionsToday, long vouchersIssuedToday,
                                    long vouchersRedeemedToday, BigDecimal pointsIssuedToday,
                                    BigDecimal pointsRedeemedToday, long fraudAttempts24h,
                                    long invoicesPending, long invoicesPaid,
                                    long expiringIn7Days, long expiringIn30Days) {}

    public record TenantDashboard(UUID tenantId, long merchants, long activeCampaigns,
                                  long vouchersOutstanding, long vouchersExpired,
                                  BigDecimal totalWalletBalance,
                                  long invoicesPending) {}

    public record MerchantDashboard(UUID merchantId, long redemptionsToday,
                                    long vouchersIssued, long vouchersRedeemed,
                                    BigDecimal pointsIssued, BigDecimal pointsRedeemed,
                                    long fraudAlerts24h,
                                    LocalDate nextInvoiceDate, BigDecimal estimatedInvoice) {}

    // ── Merchant 360 report (GET /loyalty/reports/merchants/full) ────────────
    // One row per merchant under the tenant, carrying EVERYTHING an operator
    // needs to review the merchant in one screen: identity + configuration,
    // shops, applicable rules, campaigns, lifetime points + voucher activity,
    // full billing picture, and headline stats. Assembled by
    // ReportingService.merchantFullReports; visibility is A01-scoped there.

    /** A loyalty rule as it applies to this merchant. {@code scope} says whether
     *  the row is the merchant's own override or a tenant-wide template. */
    public record RuleLine(
            UUID id,
            @Schema(example = "MERCHANT", allowableValues = {"MERCHANT", "TENANT_GLOBAL"},
                    description = "MERCHANT = rule targets this merchant specifically; TENANT_GLOBAL = tenant-wide template that also applies.")
            String scope,
            @Schema(example = "PURCHASE") String transactionType,
            @Schema(example = "1.000000", description = "Points earned per currency unit.") BigDecimal pointsPerUnit,
            @Schema(example = "1.0000") BigDecimal multiplier,
            @Schema(example = "500.0000", nullable = true) BigDecimal maxPointsPerTxn,
            @Schema(example = "default", nullable = true) String pocket,
            boolean active,
            @Schema(nullable = true) Instant startsAt,
            @Schema(nullable = true) Instant endsAt) {}

    /** A campaign that applies to this merchant (its own, or tenant-wide). */
    public record CampaignLine(
            UUID id,
            @Schema(example = "Double points December") String name,
            @Schema(example = "PURCHASE", nullable = true) String transactionType,
            @Schema(example = "2.0000") BigDecimal multiplier,
            boolean active,
            @Schema(nullable = true) Instant startsAt,
            @Schema(nullable = true) Instant endsAt,
            @Schema(example = "1842", description = "Transactions this campaign has boosted so far.")
            long matchedTransactions) {}

    /** Lifetime points activity at the merchant. */
    public record PointsSummary(
            @Schema(example = "184200.0000", description = "All-time points issued at this merchant.")
            BigDecimal issuedAllTime,
            @Schema(example = "121500.0000") BigDecimal redeemedAllTime,
            @Schema(example = "62700.0000", description = "issuedAllTime − redeemedAllTime: points originated here that are still outstanding.")
            BigDecimal netOutstanding,
            @Schema(example = "5231", description = "All-time POSTED transaction count.") long transactionCount,
            @Schema(description = "POSTED transaction count per type (PURCHASE, REDEMPTION, ...).")
            Map<String, Long> transactionsByType,
            @Schema(nullable = true, description = "When the merchant first transacted; null if never.")
            Instant firstTransactionAt,
            @Schema(nullable = true) Instant lastTransactionAt) {}

    /** Lifetime voucher activity at the merchant. */
    public record VoucherSummary(
            @Schema(example = "412") long total,
            @Schema(description = "Voucher count per status (ISSUED, DELIVERED, VIEWED, REDEEMED, PARTIALLY_USED, EXPIRED, REVOKED).")
            Map<String, Long> byStatus,
            @Schema(example = "10300.0000", description = "Summed face value of every voucher ever issued, in USD (the platform base currency). Excludes vouchers with no money face value — PERCENT, FREE_ITEM and COMBO — which are still counted in `byStatus`.")
            BigDecimal valueIssuedAllTime,
            @Schema(example = "7150.0000", description = "Summed face value of vouchers that have been (fully or partially) redeemed, in USD (the platform base currency).")
            BigDecimal valueRedeemedAllTime,
            @Schema(example = "38") long issuedLast30Days,
            @Schema(example = "22") long redeemedLast30Days) {}

    /** The merchant's full billing picture. */
    public record InvoiceSummary(
            @Schema(example = "14") long total,
            @Schema(example = "1") long pending,
            @Schema(example = "12") long paid,
            @Schema(example = "1") long overdue,
            @Schema(example = "0") long cancelled,
            @Schema(example = "1260.5000", description = "Sum of every invoice ever raised (all statuses except none — cancelled included in count only).")
            BigDecimal totalBilled,
            @Schema(example = "1100.0000") BigDecimal totalPaid,
            @Schema(example = "160.5000", description = "Sum of PENDING + OVERDUE invoice amounts.")
            BigDecimal outstandingAmount,
            @Schema(example = "2026-08-01", description = "When the next invoice will be generated, per the merchant's billing cycle.")
            LocalDate nextInvoiceDate,
            @Schema(example = "42.1500", description = "Fees accrued so far in the CURRENT (not yet invoiced) billing period, using the merchant's fee model.")
            BigDecimal estimatedCurrentPeriodFees,
            @Schema(description = "Most recent invoices, newest first (capped at 12; full history via /loyalty/invoices/merchant/{id}).")
            List<InvoiceResponse> recentInvoices) {}

    /** Headline operational stats for the merchant. */
    public record MerchantStats(
            @Schema(example = "3") long shopCount,
            @Schema(example = "3") long activeShopCount,
            @Schema(example = "1874", description = "Distinct customers who ever transacted at this merchant.")
            long uniqueCustomers,
            @Schema(example = "0") long fraudAlerts30Days,
            @Schema(example = "1") long activeCampaigns,
            @Schema(example = "5", description = "Live vouchers whose expiry falls within the next 30 days.")
            long vouchersExpiringIn30Days) {}

    /** Everything about one merchant, in one row. */
    public record MerchantFullReport(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789") UUID id,
            @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID tenantId,
            @Schema(example = "Innbucks Westgate") String name,
            @Schema(example = "Coffee", nullable = true) String category,
            @Schema(example = "USD") String currency,
            @Schema(example = "MONTHLY") Merchant.BillingCycle billingCycle,
            @Schema(example = "ACTIVE") Merchant.Status status,
            @Schema(example = "owner@innbucks.co.zw", nullable = true,
                    description = "Email of the MERCHANT_ADMIN who administers this merchant (ownership anchor).")
            String adminEmail,
            Instant createdAt,
            @Schema(description = "Fee charged when a voucher is issued.") FeeModel feeIssued,
            @Schema(description = "Fee charged when a voucher is redeemed.") FeeModel feeRedeemed,
            @Schema(description = "Every shop (outlet) under this merchant.") List<ShopResponse> shops,
            @Schema(description = "Rules that apply to this merchant: its own overrides plus tenant-global templates.")
            List<RuleLine> rules,
            @Schema(description = "Campaigns that apply to this merchant (its own plus tenant-wide).")
            List<CampaignLine> campaigns,
            PointsSummary points,
            VoucherSummary vouchers,
            InvoiceSummary invoices,
            MerchantStats stats) {}

    public record UserDashboard(UUID userId, BigDecimal totalPoints,
                                List<WalletResponse> wallets,
                                List<VoucherResponse> activeVouchers,
                                List<TransactionResponse> recentTransactions) {}

    public record MiniAppManifest(UUID id, String slug, String name,
                                  String description, String iconUrl, String entryUrl) {}

    public record FraudAttemptResponse(UUID id, String voucherCode, UUID merchantId,
                                       String reason, String detail,
                                       String deviceFingerprint, Instant createdAt) {}

    /**
     * Period-bounded points totals. Used by the per-merchant / per-user /
     * per-shop point reports. `netPoints = pointsIssued - pointsRedeemed`;
     * computed server-side so the FE doesn't have to and can never disagree.
     */
    public record PointsReport(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true,
                    description = "Subject of the report: merchant / user / shop UUID depending on which endpoint produced it.")
            UUID subjectId,
            @Schema(example = "2026-05-01")
            LocalDate from,
            @Schema(example = "2026-05-31")
            LocalDate to,
            @Schema(example = "152340.0000",
                    description = "Sum of positive `pointsDelta` rows (earn / accrual / adjustment-up).")
            BigDecimal pointsIssued,
            @Schema(example = "47820.0000",
                    description = "Sum of negative `pointsDelta` rows, returned positive (spend / redeem / adjustment-down).")
            BigDecimal pointsRedeemed,
            @Schema(example = "104520.0000",
                    description = "`pointsIssued - pointsRedeemed`. Can be negative.")
            BigDecimal netPoints,
            @Schema(example = "1872", description = "Number of POSTED transactions matching the filter.")
            long transactionCount
    ) {}

    /** One row of the points-by-type report. */
    public record PointsByTypeRow(
            @Schema(example = "PURCHASE", description = "TransactionType.")
            String type,
            @Schema(example = "1842")
            long count,
            @Schema(example = "184200.0000")
            BigDecimal pointsIssued,
            @Schema(example = "0.0000")
            BigDecimal pointsRedeemed
    ) {}

    /** One customer's points at a shop — a row of the per-shop report breakdown. */
    public record PointsByPhoneRow(
            @Schema(example = "+263771234567", description = "Customer phone number the points accrued to.")
            String phoneNumber,
            @Schema(example = "1200.0000")
            BigDecimal pointsIssued,
            @Schema(example = "300.0000")
            BigDecimal pointsRedeemed,
            @Schema(example = "900.0000", description = "pointsIssued - pointsRedeemed for this customer at this shop.")
            BigDecimal netPoints,
            @Schema(example = "14", description = "Number of POSTED transactions for this customer at the shop.")
            long transactionCount
    ) {}

    /**
     * One transaction line in the detailed per-shop points report — the
     * per-transaction breakdown behind the per-phone rollup. Amounts and points
     * are per transaction; {@code phoneNumber} is resolved from the transaction's
     * {@code userId} via the loyalty user projection.
     */
    public record ShopTransactionDetail(
            @Schema(example = "22222222-3333-4444-5555-666666666666")
            UUID id,
            @Schema(example = "2026-06-14T09:31:00Z", description = "When the transaction posted (UTC).")
            java.time.Instant createdAt,
            @Schema(example = "PURCHASE",
                    description = "TransactionType: PURCHASE, BILL_PAYMENT, QR_PAY, WALLET_TOPUP, "
                            + "POINTS_PURCHASE, PROMO, REFUND, TRANSFER, REDEMPTION, ADJUSTMENT, CARD_PAYMENT. "
                            + "A reversal posts as ADJUSTMENT and flips the original's status to REVERSED.")
            String type,
            @Schema(example = "POSTED", description = "POSTED or REVERSED.")
            String status,
            @Schema(example = "+263771234567", description = "Customer phone the points accrued to (resolved from userId).")
            String phoneNumber,
            @Schema(example = "33333333-3333-3333-3333-333333333333", description = "LoyaltyUser UUID.")
            UUID userId,
            @Schema(example = "070a95b3-b136-4b62-98a7-e7c3f09f24e2", description = "Shop/outlet the transaction occurred at.")
            UUID shopId,
            @Schema(example = "Steers Westgate", nullable = true, description = "Shop/outlet name.")
            String shopName,
            @Schema(example = "25.0000", description = "Transaction monetary amount (the purchase/spend value).")
            BigDecimal amount,
            @Schema(example = "USD")
            String currency,
            @Schema(example = "125.0000",
                    description = "Points awarded on this transaction — signed pointsDelta (+ earned, - redeemed).")
            BigDecimal pointsAwarded,
            @Schema(example = "EARN", description = "EARN (points > 0), REDEEM (points < 0) or NEUTRAL (0).")
            String direction,
            @Schema(example = "POS-8843", nullable = true, description = "Merchant/POS reference for the transaction.")
            String reference,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", nullable = true)
            UUID merchantId,
            @Schema(example = "a1a1a1a1-1111-2222-3333-444444444444", nullable = true,
                    description = "Earn/redeem rule applied, if any.")
            UUID ruleId,
            @Schema(example = "9f9f9f9f-0000-1111-2222-333333333333", nullable = true,
                    description = "Campaign attributed, if any.")
            UUID campaignId
    ) {}

    /**
     * Per-shop points report: period totals, a per-customer (phone) breakdown,
     * AND a paginated per-transaction detail. Produced by the per-shop endpoint
     * only; the merchant/user reports keep the flat {@link PointsReport}.
     */
    public record ShopPointsReport(
            @Schema(example = "c7d8e9f0-1234-5678-90ab-cdef12345678", description = "Shop UUID.")
            UUID subjectId,
            @Schema(example = "Steers Westgate", nullable = true, description = "Shop/outlet name for subjectId.")
            String shopName,
            @Schema(example = "2026-05-01")
            LocalDate from,
            @Schema(example = "2026-05-31")
            LocalDate to,
            @Schema(example = "18240.0000")
            BigDecimal pointsIssued,
            @Schema(example = "5320.0000")
            BigDecimal pointsRedeemed,
            @Schema(example = "12920.0000", description = "pointsIssued - pointsRedeemed across the shop.")
            BigDecimal netPoints,
            @Schema(example = "312", description = "Number of POSTED transactions at the shop.")
            long transactionCount,
            @Schema(description = "Per-customer breakdown, sorted by pointsIssued descending.")
            java.util.List<PointsByPhoneRow> byPhone,
            @Schema(description = "Per-transaction detail (paginated, newest first): phone number, transaction "
                    + "details, amount, and points awarded for every transaction at this shop in the period.")
            PageResponse<ShopTransactionDetail> transactions
    ) {}

    /** One bucket of the daily time-series. */
    public record PointsTimeSeriesPoint(
            @Schema(example = "2026-05-04T00:00:00Z",
                    description = "Bucket start (UTC midnight). Missing days within the range have a row with zeros so the FE can render a contiguous chart.")
            Instant bucket,
            @Schema(example = "5120.0000")
            BigDecimal pointsIssued,
            @Schema(example = "1240.0000")
            BigDecimal pointsRedeemed,
            @Schema(example = "73")
            long transactionCount
    ) {}

    // --- Typed request bodies (OWASP A03: replace untyped Map<String,?> bodies) ---

    /**
     * Manual points adjustment (operator escape hatch). {@code points} may be
     * POSITIVE (credit) or NEGATIVE (debit) — its sign is intentionally NOT
     * constrained. Replaces the former untyped {@code Map<String,Object>} body;
     * the JSON keys (userId, merchantId, points, reason) and accepted value
     * types are unchanged (Jackson binds a JSON number OR a numeric string into
     * {@code points}).
     */
    public record PointsAdjustRequestDTO(
            @Schema(example = "11111111-2222-3333-4444-555555555555", description = "Loyalty user whose balance is adjusted.")
            @NotNull UUID userId,
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", description = "Merchant the adjustment is booked against.")
            @NotNull UUID merchantId,
            @Schema(example = "250.0000", description = "Points delta — positive credits, negative debits.")
            @NotNull BigDecimal points,
            @Schema(example = "Goodwill credit", nullable = true, description = "Free-text audit note.")
            @Size(max = 1000) String reason
    ) {}

    /**
     * Optional body for a transaction reversal. Carries only a free-text
     * {@code reason}; the whole body may be omitted (reason then defaults to
     * null). Replaces the former untyped {@code Map<String,String>} body.
     */
    public record PointsReverseRequestDTO(
            @Schema(example = "Customer refund", nullable = true, description = "Why the transaction is being reversed.")
            @Size(max = 1000) String reason
    ) {}

    /**
     * Manual invoice generation for a merchant over a date range. Replaces the
     * former untyped {@code Map<String,String>} body; the JSON keys (merchantId,
     * periodStart, periodEnd) and their ISO-8601 date values are unchanged.
     */
    public record InvoiceGenerateRequestDTO(
            @Schema(example = "b4c0d2e3-2345-6789-abcd-ef0123456789", description = "Merchant to invoice.")
            @NotNull UUID merchantId,
            @Schema(example = "2026-05-01", description = "Billing period start (inclusive, ISO-8601 date).")
            @NotNull LocalDate periodStart,
            @Schema(example = "2026-05-31", description = "Billing period end (inclusive, ISO-8601 date).")
            @NotNull LocalDate periodEnd
    ) {}

    // Internal S2S (X-Internal-Token) shop-checkout body. Untyped-Map replacement;
    // every field is optional because ShopCheckoutService.checkout does the
    // validation and returns the specific error codes, exactly as before.
    public record ShopCheckoutInternalRequestDTO(
            UUID shopId,
            String phoneNumber,
            BigDecimal cashAmount,
            BigDecimal pointsAmount,
            String reference
    ) {}

    // Internal S2S (X-Internal-Token) ticketing earn body. TicketingLoyaltyService.earn
    // validates organizerUuid / phoneNumber / cashAmount and returns typed 4xx errors.
    public record TicketingEarnRequestDTO(
            UUID organizerUuid,
            String phoneNumber,
            BigDecimal cashAmount,
            String reference
    ) {}

    // Internal S2S (X-Internal-Token) ticketing redeem body. TicketingLoyaltyService.redeem
    // validates organizerUuid / phoneNumber / points and returns typed 4xx errors.
    public record TicketingRedeemRequestDTO(
            UUID organizerUuid,
            String phoneNumber,
            BigDecimal points,
            String reference
    ) {}
}
