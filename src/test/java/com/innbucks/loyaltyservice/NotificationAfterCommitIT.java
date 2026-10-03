package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.integration.MemberActivityNotifier;
import com.innbucks.loyaltyservice.integration.NotificationGateway;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.service.MerchantService;
import com.innbucks.loyaltyservice.service.VoucherService;
import com.innbucks.loyaltyservice.testsupport.ControllerSecurityTestBase;
import com.innbucks.loyaltyservice.testsupport.MerchantFixtures;
import com.innbucks.loyaltyservice.testsupport.TestJwtFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real transactions, real Postgres, real services — the two transaction-shape
 * guarantees of the notification path, end to end:
 *
 * <ol>
 *   <li><b>Customer messages are handed off only after COMMIT.</b> The notifier
 *       beans are mocks (so the hand-off is observed synchronously, inside the
 *       {@code afterCommit} callback); each answer reads the row from ANOTHER
 *       thread — a separate connection, so it sees committed data only. A send
 *       made from inside the transaction would see nothing there.</li>
 *   <li><b>The STAFF_RECIPIENT lookup happens before the earn's transaction
 *       opens.</b> The user-service client mock records whether a transaction
 *       was active when it was called — through the real controller.</li>
 * </ol>
 */
class NotificationAfterCommitIT extends ControllerSecurityTestBase {

    @MockitoBean NotificationGateway notificationGateway;
    @MockitoBean MemberActivityNotifier memberNotifier;

    @Autowired MerchantService merchantService;
    @Autowired VoucherService voucherService;
    @Autowired VoucherRepository voucherRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private UUID tenantId;
    private UUID merchantId;

    @BeforeEach
    void seed() {
        tenantId = newTenant("notify-tx");
        merchantId = MerchantFixtures.createAsPlatform(merchantService, tenantId,
                new Dtos.MerchantRequest("After Commit Cafe " + UUID.randomUUID(), "F&B", "USD",
                        Merchant.BillingCycle.MONTHLY,
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.05"), null),
                        new Dtos.FeeModel(Merchant.FeeType.FIXED, new BigDecimal("0.10"), null))).id();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "it-fixture", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /** Read on another thread = another connection: only COMMITTED rows are visible. */
    private Optional<Voucher> committedVoucher(UUID id) throws Exception {
        return CompletableFuture.supplyAsync(() -> voucherRepository.findById(id))
                .get(10, TimeUnit.SECONDS);
    }

    private Dtos.VoucherResponse issueGift() {
        return voucherService.issue(tenantId, new Dtos.IssueVoucherRequest(
                merchantId, null, new BigDecimal("5"), "USD", null,
                "+263771110001", "Sedrick Nyanyiwa", null,
                "Tawanda Mpofu", "+263771110002",
                Voucher.DeliveryChannel.WHATSAPP, null));
    }

    @Test
    void anIssuedVoucher_isDeliveredAndSenderCopied_onlyOnceItsRowHasCommitted() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            seen.add("deliver committed=" + committedVoucher(v.getId()).isPresent());
            return null;
        }).when(notificationGateway).deliver(any(), any());
        doAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            seen.add("sender committed=" + committedVoucher(v.getId()).isPresent());
            return null;
        }).when(notificationGateway).deliverSenderCopy(any(), any());

        issueGift();

        // Same messages, same order, same recipients as before — just later.
        assertThat(seen).containsExactly("deliver committed=true", "sender committed=true");
        verify(notificationGateway).deliver(any(), eq("+263771110001"));
        verify(notificationGateway).deliverSenderCopy(any(), eq("+263771110002"));
    }

    @Test
    void anIssueThatRollsBack_sendsNothing() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            issueGift();
            status.setRollbackOnly();
        });

        verify(notificationGateway, never()).deliver(any(), any());
        verify(notificationGateway, never()).deliverSenderCopy(any(), any());
    }

    @Test
    void aTransfer_tellsBothSidesAndDeliversTheRotatedCode_onlyAfterItCommits() throws Exception {
        Dtos.VoucherResponse issued = issueGift();
        String originalCode = issued.code();
        org.mockito.Mockito.clearInvocations(notificationGateway, memberNotifier);

        AtomicInteger committedSends = new AtomicInteger();
        doAnswer(inv -> {
            Voucher v = inv.getArgument(0);
            // The code the holder is sent is the ROTATED one, and it is already
            // the committed one — a send from inside the transaction would read
            // the original code back from another connection.
            Voucher committed = committedVoucher(v.getId()).orElseThrow();
            assertThat(committed.getCode()).isEqualTo(v.getCode()).isNotEqualTo(originalCode);
            committedSends.incrementAndGet();
            return null;
        }).when(notificationGateway).deliver(any(), any());
        doAnswer(inv -> {
            Voucher committed = committedVoucher(issued.id()).orElseThrow();
            assertThat(committed.getTransferredAt()).isNotNull();
            committedSends.incrementAndGet();
            return null;
        }).when(memberNotifier).notifyVoucherReceived(any(), any(), any(), any());

        voucherService.transfer(tenantId, issued.id(),
                new Dtos.VoucherTransferRequest(null, "+263771110003", null));

        assertThat(committedSends.get()).isEqualTo(2);
        verify(memberNotifier).notifyVoucherReceived(eq("+263771110003"), any(), eq("USD"), any());
        verify(memberNotifier).notifyVoucherSent(eq("+263771110001"), any(), eq("USD"));
        verify(notificationGateway).deliver(any(), eq("+263771110003"));
        // Transfer never sends a sender copy (it would hand the rotation back).
        verify(notificationGateway, never()).deliverSenderCopy(any(), any());
    }

    @Test
    void aTransferThatRollsBack_sendsNothing() {
        Dtos.VoucherResponse issued = issueGift();
        org.mockito.Mockito.clearInvocations(notificationGateway, memberNotifier);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            voucherService.transfer(tenantId, issued.id(),
                    new Dtos.VoucherTransferRequest(null, "+263771110004", null));
            status.setRollbackOnly();
        });

        verify(memberNotifier, never()).notifyVoucherReceived(any(), any(), any(), any());
        verify(memberNotifier, never()).notifyVoucherSent(any(), any(), any());
        verify(notificationGateway, never()).deliver(any(), any());
    }

    // --- STAFF_RECIPIENT: the user-service lookup happens before the earn's transaction ---

    private String earnBody(String phone, String reference) {
        return """
                {"merchantId":"%s","assigneePhone":"%s","type":"PURCHASE",
                 "amount":10,"currency":"USD","reference":"%s"}
                """.formatted(merchantId, phone, reference);
    }

    @Test
    void aTypedEarnToAColleague_isStillRefused_andTheStaffLookupRanOutsideAnyTransaction() throws Exception {
        String colleague = "+263771119999";
        List<Boolean> txActiveAtLookup = new CopyOnWriteArrayList<>();
        when(userServiceClient.merchantStaffPhones(merchantId)).thenAnswer(inv -> {
            txActiveAtLookup.add(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(Set.of(colleague));
        });

        mockMvc.perform(post("/loyalty/transactions")
                        .header("Authorization", bearer(TestJwtFactory.superAdmin(jwtSecret)))
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(earnBody(colleague, "R-" + UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STAFF_RECIPIENT"));

        // One lookup, made by the pre-load before the transaction opened; the
        // guard inside the transaction read the cache.
        assertThat(txActiveAtLookup).containsExactly(false);
    }

    @Test
    void aFailedStaffLookup_stillFailsOpen_andTheEarnGoesThrough() throws Exception {
        List<Boolean> txActiveAtLookup = new CopyOnWriteArrayList<>();
        when(userServiceClient.merchantStaffPhones(merchantId)).thenAnswer(inv -> {
            txActiveAtLookup.add(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.empty(); // user-service unreachable / unknown answer
        });

        mockMvc.perform(post("/loyalty/transactions")
                        .header("Authorization", bearer(TestJwtFactory.superAdmin(jwtSecret)))
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(earnBody("+263771118888", "R-" + UUID.randomUUID())))
                .andExpect(status().isCreated());

        assertThat(txActiveAtLookup).containsExactly(false);
    }

    @Test
    void anAuthoritativeEmptyStaffList_earnsNormally() throws Exception {
        when(userServiceClient.merchantStaffPhones(merchantId)).thenReturn(Optional.of(Set.of()));

        mockMvc.perform(post("/loyalty/transactions")
                        .header("Authorization", bearer(TestJwtFactory.superAdmin(jwtSecret)))
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(earnBody("+263771117777", "R-" + UUID.randomUUID())))
                .andExpect(status().isCreated());
    }
}
