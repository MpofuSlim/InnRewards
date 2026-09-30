package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.dto.SupportDtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.PhoneRegistration;
import com.innbucks.loyaltyservice.entity.PointsLedger;
import com.innbucks.loyaltyservice.entity.SupportActivity;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.entity.VoucherPurchaseOrder;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyRefreshTokenRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.PhoneRegistrationRepository;
import com.innbucks.loyaltyservice.repository.PointsLedgerRepository;
import com.innbucks.loyaltyservice.repository.SupportMessageRepository;
import com.innbucks.loyaltyservice.repository.SupportNoteRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.repository.VoucherPurchaseOrderRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.security.SupportAgent;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import com.innbucks.loyaltyservice.util.PhoneSpellings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The read side of customer support: find a customer by phone across EVERY
 * tenant, and show them.
 *
 * <p><b>Platform-wide by construction.</b> Nothing here reads
 * {@code X-Tenant-Id}, {@code TenantContext}, or any of the role lists the
 * merchant surfaces authorize with ({@code requireCallerOwnsOrIsAdmin},
 * {@code MerchantAuthz}, ...). The permission check on the controller is the
 * whole authorization, and it is a permission, not a role — so widening support
 * never means widening a merchant role, and no merchant role ever reaches this.
 *
 * <p><b>Every drill-down is tied to a logged lookup.</b> A drill-down takes a
 * {@code lookupId}, never a phone: the id resolves to its phone only for the
 * agent who looked the customer up, only while the lookup is live, and every
 * read writes its own activity row. So "which customers did this agent look at"
 * is answerable, and there is no way to page through a customer's history
 * without having looked them up first.
 */
@Service
@Slf4j
public class SupportCustomerService {

    /** How many rows each 360 section inlines. */
    static final int RECENT = 5;
    static final int STATEMENT_FIRST_PAGE = 10;

    /**
     * Stand-in for an empty holder-account list: JPQL {@code IN ()} is not
     * valid SQL. No projection has the nil UUID as its id.
     */
    private static final List<UUID> NO_ACCOUNTS = List.of(new UUID(0L, 0L));

    private final UserService userService;
    private final SupportActivityService activity;
    private final PhoneRegistrationRepository registrations;
    private final LoyaltyUserRepository users;
    private final TenantRepository tenants;
    private final WalletRepository wallets;
    private final LoyaltyTransactionRepository transactions;
    private final PointsLedgerRepository ledger;
    private final VoucherRepository vouchers;
    private final VoucherPurchaseOrderRepository orders;
    private final MerchantRepository merchants;
    private final LoyaltyRefreshTokenRepository refreshTokens;
    private final SupportNoteRepository notes;
    private final SupportMessageRepository messages;
    private final UserServiceClient userServiceClient;

    public SupportCustomerService(UserService userService, SupportActivityService activity,
                                  PhoneRegistrationRepository registrations, LoyaltyUserRepository users,
                                  TenantRepository tenants, WalletRepository wallets,
                                  LoyaltyTransactionRepository transactions, PointsLedgerRepository ledger,
                                  VoucherRepository vouchers, VoucherPurchaseOrderRepository orders,
                                  MerchantRepository merchants, LoyaltyRefreshTokenRepository refreshTokens,
                                  SupportNoteRepository notes, SupportMessageRepository messages,
                                  UserServiceClient userServiceClient) {
        this.userService = userService;
        this.activity = activity;
        this.registrations = registrations;
        this.users = users;
        this.tenants = tenants;
        this.wallets = wallets;
        this.transactions = transactions;
        this.ledger = ledger;
        this.vouchers = vouchers;
        this.orders = orders;
        this.merchants = merchants;
        this.refreshTokens = refreshTokens;
        this.notes = notes;
        this.messages = messages;
        this.userServiceClient = userServiceClient;
    }

    /** A live lookup, resolved: the customer it stands for. */
    public record Customer(UUID lookupId, String phone, List<String> spellings, List<LoyaltyUser> memberships) {

        public List<UUID> membershipIds() {
            return memberships.stream().map(LoyaltyUser::getId).toList();
        }

        /** For a JPQL {@code IN}: never empty. */
        List<UUID> holderAccounts() {
            List<UUID> ids = membershipIds();
            return ids.isEmpty() ? NO_ACCOUNTS : ids;
        }
    }

    public enum VoucherRole { HELD, SENT, TRANSFERRED }

    // ---- Lookup ----

    /**
     * Looks a customer up by phone. NOT transactional on purpose: a lookup that
     * finds nobody still writes its SEARCH row, and that row must commit even
     * though the call then answers 404.
     */
    public SupportDtos.LookupResponse lookup(SupportAgent agent, String rawPhone) {
        String phone = normalise(rawPhone);
        List<String> spellings = PhoneSpellings.of(phone);
        if (!onRecord(phone, spellings)) {
            activity.record(agent, SupportActivity.Action.SEARCH, null, null,
                    SupportActivityService.detail("phone", MsisdnMasking.mask(phone), "found", false));
            throw new LoyaltyException(HttpStatus.NOT_FOUND, "customer_not_found",
                    "No loyalty customer is on record for that phone number.");
        }
        SupportActivity lookup = activity.record(agent, SupportActivity.Action.CUSTOMER_LOOKUP,
                SupportActivity.SUBJECT_PHONE, phone, SupportActivityService.detail("found", true));
        Customer customer = new Customer(lookup.getId(), phone, spellings, membershipsOf(phone));
        return new SupportDtos.LookupResponse(lookup.getId(), activity.expiresAt(lookup), build360(customer));
    }

    /**
     * The 360 again, for a live lookup. Not transactional for the same reason as
     * {@link #lookup}'s 360: it includes a best-effort call to user-service for
     * the tier, and no database connection should be held across somebody
     * else's network call. The VIEW row commits in its own short transaction.
     */
    public SupportDtos.LookupResponse view(SupportAgent agent, UUID lookupId) {
        SupportActivity lookup = activity.requireLiveLookup(agent, lookupId);
        Customer customer = customerOf(lookup);
        logView(agent, customer, SupportActivity.Action.VIEW_CUSTOMER);
        return new SupportDtos.LookupResponse(lookupId, activity.expiresAt(lookup), build360(customer));
    }

    /** Resolves a live lookup for another support service; writes nothing. */
    @Transactional(readOnly = true)
    public Customer resolve(SupportAgent agent, UUID lookupId) {
        return customerOf(activity.requireLiveLookup(agent, lookupId));
    }

    // ---- Drill-downs ----

    @Transactional
    public PageResponse<SupportDtos.TransactionLine> transactions(SupportAgent agent, UUID lookupId,
                                                                  int page, int size) {
        Customer customer = customerOf(activity.requireLiveLookup(agent, lookupId));
        Pageable pageable = PageRequest.of(SupportPaging.page(page), SupportPaging.size(size));
        logView(agent, customer, SupportActivity.Action.VIEW_TRANSACTIONS,
                "page", pageable.getPageNumber(), "size", pageable.getPageSize());
        return transactionPage(customer, pageable);
    }

    /** Every points_ledger entry across the phone's wallets — the balance's own history. */
    @Transactional
    public PageResponse<SupportDtos.LedgerLine> ledger(SupportAgent agent, UUID lookupId, int page, int size) {
        Customer customer = customerOf(activity.requireLiveLookup(agent, lookupId));
        Pageable pageable = PageRequest.of(SupportPaging.page(page), SupportPaging.size(size));
        logView(agent, customer, SupportActivity.Action.VIEW_LEDGER,
                "page", pageable.getPageNumber(), "size", pageable.getPageSize());
        List<UUID> walletIds = wallets.findByPhoneNumber(customer.phone()).stream().map(Wallet::getId).toList();
        if (walletIds.isEmpty()) {
            return PageResponse.from(Page.<SupportDtos.LedgerLine>empty(pageable));
        }
        return PageResponse.from(ledger.findByWalletIdInOrderByCreatedAtDesc(walletIds, pageable)
                .map(SupportCustomerService::toLedgerLine));
    }

    @Transactional
    public PageResponse<SupportDtos.VoucherLine> vouchers(SupportAgent agent, UUID lookupId, VoucherRole role,
                                                          int page, int size) {
        Customer customer = customerOf(activity.requireLiveLookup(agent, lookupId));
        VoucherRole r = role == null ? VoucherRole.HELD : role;
        Pageable pageable = PageRequest.of(SupportPaging.page(page), SupportPaging.size(size));
        logView(agent, customer, SupportActivity.Action.VIEW_VOUCHERS,
                "role", r, "page", pageable.getPageNumber(), "size", pageable.getPageSize());
        Page<Voucher> found = switch (r) {
            case HELD -> vouchers.findHeldByPhone(customer.spellings(), customer.holderAccounts(), pageable);
            case SENT -> vouchers.findBySenderPhoneInOrderByIssuedAtDesc(customer.spellings(), pageable);
            case TRANSFERRED -> vouchers.findByTransferredFromPhoneInOrderByTransferredAtDesc(
                    customer.spellings(), pageable);
        };
        Map<UUID, String> names = merchantNames(found.getContent().stream().map(Voucher::getMerchantId));
        return PageResponse.from(found.map(v -> toVoucherLine(v, customer, names)));
    }

    @Transactional
    public PageResponse<SupportDtos.OrderLine> voucherOrders(SupportAgent agent, UUID lookupId, int page, int size) {
        Customer customer = customerOf(activity.requireLiveLookup(agent, lookupId));
        Pageable pageable = PageRequest.of(SupportPaging.page(page), SupportPaging.size(size));
        logView(agent, customer, SupportActivity.Action.VIEW_VOUCHER_ORDERS,
                "page", pageable.getPageNumber(), "size", pageable.getPageSize());
        Page<VoucherPurchaseOrder> found = orders.findByAnyPhone(customer.spellings(), pageable);
        Map<UUID, String> names = merchantNames(found.getContent().stream().map(VoucherPurchaseOrder::getMerchantId));
        Set<String> spellings = new HashSet<>(customer.spellings());
        return PageResponse.from(found.map(o -> toOrderLine(o, spellings, names)));
    }

    /** The membership (projection) {@code userId}, if and only if it belongs to this customer's phone. */
    public LoyaltyUser requireMembership(Customer customer, UUID userId) {
        return customer.memberships().stream()
                .filter(u -> u.getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new LoyaltyException(HttpStatus.NOT_FOUND, "membership_not_found",
                        "This customer has no loyalty membership with that id."));
    }

    /**
     * Whether {@code v} is held by this customer — the exact precedence
     * {@code VoucherService.holderPhone} applies: the assignee phone when there
     * is one, else the assigned account's.
     */
    public static boolean holds(Customer customer, Voucher v) {
        if (v.getAssigneePhone() != null && !v.getAssigneePhone().isBlank()) {
            return customer.spellings().contains(v.getAssigneePhone());
        }
        return v.getAssignedUserId() != null && customer.membershipIds().contains(v.getAssignedUserId());
    }

    public void logView(SupportAgent agent, Customer customer, SupportActivity.Action action, Object... extra) {
        Object[] kv = new Object[extra.length + 2];
        kv[0] = "lookupId";
        kv[1] = customer.lookupId();
        System.arraycopy(extra, 0, kv, 2, extra.length);
        activity.record(agent, action, SupportActivity.SUBJECT_PHONE, customer.phone(),
                SupportActivityService.detail(kv));
    }

    // ---- The 360 ----

    SupportDtos.Customer360 build360(Customer customer) {
        String phone = customer.phone();
        List<LoyaltyUser> memberships = customer.memberships();

        SupportDtos.Registration registration = registrations.findById(phone)
                .map(SupportCustomerService::toRegistration)
                .orElse(new SupportDtos.Registration(false, null, null, null, null));

        Map<UUID, String> tenantNames = tenants.findAllById(
                        memberships.stream().map(LoyaltyUser::getTenantId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Tenant::getId, Tenant::getName, (a, b) -> a));
        List<SupportDtos.Membership> membershipLines = memberships.stream()
                .map(u -> toMembership(u, tenantNames.get(u.getTenantId())))
                .toList();

        List<Wallet> customerWallets = wallets.findByPhoneNumber(phone);
        BigDecimal total = customerWallets.stream()
                .map(w -> w.getBalance() == null ? BigDecimal.ZERO : w.getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        SupportDtos.WalletSummary wallet = new SupportDtos.WalletSummary(total,
                customerWallets.stream()
                        .sorted(Comparator.comparing(Wallet::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(SupportCustomerService::toWalletLine).toList());

        Page<LoyaltyTransaction> statement = customer.memberships().isEmpty()
                ? Page.empty(PageRequest.of(0, STATEMENT_FIRST_PAGE))
                : transactions.findByUserIdInOrderByCreatedAtDesc(customer.membershipIds(),
                        PageRequest.of(0, STATEMENT_FIRST_PAGE));

        List<String> spellings = customer.spellings();
        List<UUID> holderAccounts = customer.holderAccounts();
        long heldTotal = vouchers.countHeldByPhone(spellings, holderAccounts);
        long heldLive = vouchers.countHeldByPhoneInStatus(spellings, holderAccounts, Voucher.LIVE_STATUSES);
        long sent = vouchers.countBySenderPhoneIn(spellings);
        long transferred = vouchers.countByTransferredFromPhoneIn(spellings);
        List<Voucher> recentHeld = heldTotal == 0 ? List.of()
                : vouchers.findHeldByPhone(spellings, holderAccounts, PageRequest.of(0, RECENT)).getContent();

        Page<VoucherPurchaseOrder> recentOrders = orders.findByAnyPhone(spellings, PageRequest.of(0, RECENT));

        // ONE merchant-name read for every section of the page.
        Map<UUID, String> names = merchantNames(Stream.of(
                statement.getContent().stream().map(LoyaltyTransaction::getMerchantId),
                recentHeld.stream().map(Voucher::getMerchantId),
                recentOrders.getContent().stream().map(VoucherPurchaseOrder::getMerchantId))
                .flatMap(Function.identity()));

        Set<String> spellingSet = new HashSet<>(spellings);
        return new SupportDtos.Customer360(
                MsisdnMasking.mask(phone),
                registration,
                membershipLines,
                wallet,
                PageResponse.from(statement.map(t -> toTransactionLine(t, names))),
                new SupportDtos.VoucherSummary(heldLive, heldTotal, sent, transferred,
                        recentHeld.stream().map(v -> toVoucherLine(v, customer, names)).toList()),
                new SupportDtos.OrderSummary(recentOrders.getTotalElements(),
                        recentOrders.getContent().stream().map(o -> toOrderLine(o, spellingSet, names)).toList()),
                new SupportDtos.Sessions(refreshTokens.countActiveChains(phone, Instant.now())),
                tierOf(phone),
                notes.countBySubjectKindAndSubjectId(SupportActivity.SUBJECT_PHONE, phone),
                messages.countByRecipientMsisdn(phone));
    }

    PageResponse<SupportDtos.TransactionLine> transactionPage(Customer customer, Pageable pageable) {
        if (customer.memberships().isEmpty()) {
            return PageResponse.from(Page.<SupportDtos.TransactionLine>empty(pageable));
        }
        Page<LoyaltyTransaction> page = transactions.findByUserIdInOrderByCreatedAtDesc(
                customer.membershipIds(), pageable);
        Map<UUID, String> names = merchantNames(page.getContent().stream().map(LoyaltyTransaction::getMerchantId));
        return PageResponse.from(page.map(t -> toTransactionLine(t, names)));
    }

    // ---- Helpers ----

    /**
     * "On record" = the phone appears anywhere loyalty keeps one: a registration
     * (live or revoked), a membership, a wallet, a voucher in any role, or a
     * voucher order in any role. Cheapest checks first; each is one indexed
     * existence probe.
     */
    boolean onRecord(String phone, List<String> spellings) {
        return registrations.existsById(phone)
                || users.existsByPhoneNumber(phone)
                || wallets.existsByPhoneNumber(phone)
                || vouchers.existsByAssigneePhoneInOrSenderPhoneInOrTransferredFromPhoneIn(spellings, spellings, spellings)
                || orders.existsByPayerPhoneInOrAssigneePhoneInOrSenderPhoneIn(spellings, spellings, spellings);
    }

    private Customer customerOf(SupportActivity lookup) {
        String phone = lookup.getSubjectId();
        return new Customer(lookup.getId(), phone, PhoneSpellings.of(phone), membershipsOf(phone));
    }

    private List<LoyaltyUser> membershipsOf(String phone) {
        List<LoyaltyUser> found = new ArrayList<>(users.findByPhoneNumber(phone));
        found.sort(Comparator.comparing(LoyaltyUser::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return found;
    }

    private String normalise(String rawPhone) {
        try {
            return userService.normalizePhone(rawPhone);
        } catch (LoyaltyException e) {
            // UserService's message echoes the raw input; this one does not.
            throw LoyaltyException.badRequest("invalid_msisdn", "That is not a valid phone number.");
        }
    }

    private SupportDtos.Tier tierOf(String phone) {
        try {
            return userServiceClient.getCustomerTier(phone)
                    .map(t -> new SupportDtos.Tier(t.currentTier(), t.nextTier()))
                    .orElse(null);
        } catch (RuntimeException e) {
            // Best-effort by contract: the 360 is worth more without a tier than not at all.
            log.warn("Support 360: tier lookup failed for {}: {}", MsisdnMasking.mask(phone), e.toString());
            return null;
        }
    }

    private Map<UUID, String> merchantNames(Stream<UUID> ids) {
        Set<UUID> wanted = ids.filter(Objects::nonNull).collect(Collectors.toSet());
        if (wanted.isEmpty()) {
            return Map.of();
        }
        return merchants.findAllById(wanted).stream()
                .collect(Collectors.toMap(Merchant::getId, Merchant::getName, (a, b) -> a));
    }

    private static String mask(String phone) {
        return phone == null || phone.isBlank() ? null : MsisdnMasking.mask(phone);
    }

    private static SupportDtos.Registration toRegistration(PhoneRegistration r) {
        return new SupportDtos.Registration(r.isLive(),
                r.getSource() == null ? null : r.getSource().name(),
                r.getRegisteredAt(), r.getRevokedAt(), r.getRevokedReason());
    }

    public static SupportDtos.Membership toMembership(LoyaltyUser u, String tenantName) {
        return new SupportDtos.Membership(u.getTenantId(), tenantName, u.getId(),
                u.getStatus() == null ? null : u.getStatus().name(),
                u.getStatusReason() == null ? null : u.getStatusReason().name(),
                u.getCreatedAt());
    }

    private static SupportDtos.WalletLine toWalletLine(Wallet w) {
        return new SupportDtos.WalletLine(w.getId(), w.getLabel(),
                w.getType() == null ? null : w.getType().name(), w.getPocket(), w.getBalance(), w.getLockedUntil());
    }

    private static SupportDtos.TransactionLine toTransactionLine(LoyaltyTransaction t, Map<UUID, String> names) {
        return new SupportDtos.TransactionLine(t.getId(), t.getTenantId(), t.getMerchantId(),
                names.get(t.getMerchantId()), t.getUserId(),
                t.getType() == null ? null : t.getType().name(),
                t.getStatus() == null ? null : t.getStatus().name(),
                t.getAmount(), t.getCurrency(), t.getPointsDelta(), t.getReference(), t.getReversesId(),
                t.getShopId(), t.getPostedBy(), t.getChannel() == null ? null : t.getChannel().name(),
                t.getCreatedAt());
    }

    private static SupportDtos.LedgerLine toLedgerLine(PointsLedger p) {
        return new SupportDtos.LedgerLine(p.getId(), p.getWalletId(), p.getTenantId(), p.getTransactionId(),
                p.getDelta(), p.getBalanceAfter(), p.getReason(), p.getCreatedAt());
    }

    /** No code, ever — see {@link SupportDtos}. */
    static SupportDtos.VoucherLine toVoucherLine(Voucher v, Customer customer, Map<UUID, String> names) {
        String holder = mask(v.getAssigneePhone());
        if (holder == null && v.getAssignedUserId() != null
                && customer.membershipIds().contains(v.getAssignedUserId())) {
            holder = mask(customer.phone());
        }
        return new SupportDtos.VoucherLine(v.getId(), v.getTenantId(), v.getMerchantId(),
                names.get(v.getMerchantId()),
                v.getStatus() == null ? null : v.getStatus().name(),
                v.getVoucherType() == null ? null : v.getVoucherType().name(),
                v.getValue(), v.getCurrency(),
                holder, v.getAssigneeName(),
                mask(v.getSenderPhone()), v.getSenderName(),
                mask(v.getTransferredFromPhone()),
                v.getUsesRemaining(), v.getIssuedAt(), v.getDeliveredAt(), v.getViewedAt(),
                v.getRedeemedAt(), v.getExpiresAt(), v.getTransferredAt(), v.getCampaignSource());
    }

    private static SupportDtos.OrderLine toOrderLine(VoucherPurchaseOrder o, Collection<String> spellings,
                                                     Map<UUID, String> names) {
        List<String> roles = new ArrayList<>(3);
        if (o.getPayerPhone() != null && spellings.contains(o.getPayerPhone())) roles.add("PAYER");
        if (o.getAssigneePhone() != null && spellings.contains(o.getAssigneePhone())) roles.add("RECIPIENT");
        if (o.getSenderPhone() != null && spellings.contains(o.getSenderPhone())) roles.add("SENDER");
        return new SupportDtos.OrderLine(o.getId(), o.getOrderRef(), roles, o.getTenantId(), o.getMerchantId(),
                names.get(o.getMerchantId()),
                o.getStatus() == null ? null : o.getStatus().name(),
                o.getAmount(), o.getCurrency(),
                mask(o.getPayerPhone()), mask(o.getAssigneePhone()), mask(o.getSenderPhone()),
                o.getPaidVia() == null ? null : o.getPaidVia().name(),
                o.getPaidAt(), o.getExpiresAt(), o.getCreatedAt(), o.getVoucherId());
    }
}
