package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Voucher;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.security.CallerDetails;
import com.innbucks.loyaltyservice.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /loyalty/users/me/transactions} and {@code /me/vouchers}: the
 * authenticated, cross-tenant twins of the public statement and voucher list,
 * so the customer app can stop calling {@code /loyalty/public/**}.
 *
 * <p>Both are keyed by the token's phone alone, like the wallet: there is no
 * path variable or filter, so they cannot be pointed at another customer.
 */
class MeStatementAndVouchersTest {

    private static final String PHONE = "+263777224008";
    private static final UUID ACCOUNT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ACCOUNT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private LoyaltyUserRepository users;
    private VoucherRepository vouchers;
    private TransactionService transactions;
    private MeController controller;

    @BeforeEach
    void setUp() {
        users = mock(LoyaltyUserRepository.class);
        vouchers = mock(VoucherRepository.class);
        transactions = mock(TransactionService.class);
        controller = new MeController(users, vouchers, mock(WalletRepository.class), transactions);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String phone) {
        var auth = new UsernamePasswordAuthenticationToken(
                "customer@test.local", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        auth.setDetails(new CallerDetails(null, null, phone, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void holdsAccountsInTwoTenants() {
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of(
                account(ACCOUNT_A, TENANT_A), account(ACCOUNT_B, TENANT_B)));
    }

    private static LoyaltyUser account(UUID id, UUID tenantId) {
        LoyaltyUser u = new LoyaltyUser();
        u.setId(id);
        u.setTenantId(tenantId);
        u.setPhoneNumber(PHONE);
        u.setStatus(LoyaltyUser.Status.ACTIVE);
        return u;
    }

    private static Voucher voucher(UUID holder, UUID tenantId) {
        Voucher v = new Voucher();
        v.setId(UUID.randomUUID());
        v.setTenantId(tenantId);
        v.setCode("4829137605128368");
        v.setStatus(Voucher.Status.ISSUED);
        v.setAssignedUserId(holder);
        v.setAssigneePhone(PHONE);
        v.setValue(new BigDecimal("50.0000"));
        v.setRemainingBalance(new BigDecimal("50.0000"));
        v.setCurrency("USD");
        v.setUsesRemaining(1);
        v.setIssuedAt(Instant.parse("2026-09-01T08:00:00Z"));
        return v;
    }

    // ---- statement -------------------------------------------------------

    @Test
    void statement_coversEveryAccountOfTheTokenPhone() {
        authenticateAs(PHONE);
        holdsAccountsInTwoTenants();
        Pageable page = PageRequest.of(0, 20);
        when(transactions.statementForPhone(List.of(ACCOUNT_A, ACCOUNT_B), page))
                .thenReturn(Page.empty(page));

        ApiResult<PageResponse<Dtos.TransactionResponse>> body = controller.transactions(page).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo("Transactions retrieved successfully");
        verify(users).findByPhoneNumber(PHONE);
        verify(transactions).statementForPhone(List.of(ACCOUNT_A, ACCOUNT_B), page);
    }

    @Test
    void statement_capsThePageAt100_andDefaultsTo20() {
        authenticateAs(PHONE);
        holdsAccountsInTwoTenants();
        when(transactions.statementForPhone(anyList(), any())).thenReturn(Page.empty());
        ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);

        controller.transactions(PageRequest.of(2, 5000));
        controller.transactions(Pageable.unpaged());

        verify(transactions, org.mockito.Mockito.times(2)).statementForPhone(anyList(), asked.capture());
        assertThat(asked.getAllValues().get(0).getPageSize()).isEqualTo(100);
        assertThat(asked.getAllValues().get(0).getPageNumber()).isEqualTo(2);
        assertThat(asked.getAllValues().get(1).getPageSize()).isEqualTo(20);
    }

    @Test
    void statement_withNoPhoneClaim_isRefused_andReadsNothing() {
        authenticateAs(null);

        assertThatThrownBy(() -> controller.transactions(PageRequest.of(0, 20)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessage("/me endpoints require a JWT with a phoneNumber claim");
        verify(users, never()).findByPhoneNumber(anyString());
        verify(transactions, never()).statementForPhone(anyList(), any());
    }

    // ---- vouchers --------------------------------------------------------

    @Test
    void vouchers_listTheLiveVouchersOfEveryAccount_withTheirTenant() {
        authenticateAs(PHONE);
        holdsAccountsInTwoTenants();
        Pageable page = PageRequest.of(0, 20);
        when(vouchers.findByAssignedUserIdInAndStatusIn(
                List.of(ACCOUNT_A, ACCOUNT_B), Voucher.LIVE_STATUSES, page))
                .thenReturn(new PageImpl<>(List.of(voucher(ACCOUNT_A, TENANT_A), voucher(ACCOUNT_B, TENANT_B)),
                        page, 2));

        ApiResult<PageResponse<Dtos.VoucherResponse>> body = controller.vouchers(page).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo("Active vouchers retrieved successfully");
        assertThat(body.getData().getContent())
                .extracting(Dtos.VoucherResponse::tenantId)
                .containsExactly(TENANT_A, TENANT_B);
        // The holder's own vouchers: the code is what they present to redeem.
        assertThat(body.getData().getContent())
                .extracting(Dtos.VoucherResponse::code)
                .containsOnly("4829137605128368");
    }

    @Test
    void vouchers_forAPhoneWithNoAccount_isAnEmptyPage_withoutAQuery() {
        authenticateAs(PHONE);
        when(users.findByPhoneNumber(PHONE)).thenReturn(List.of());

        ApiResult<PageResponse<Dtos.VoucherResponse>> body = controller.vouchers(PageRequest.of(0, 20)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getData().getContent()).isEmpty();
        assertThat(body.getData().getTotalElements()).isZero();
        verify(vouchers, never()).findByAssignedUserIdInAndStatusIn(anyList(), anyList(), any());
    }

    @Test
    void vouchers_capThePageAt100() {
        authenticateAs(PHONE);
        holdsAccountsInTwoTenants();
        when(vouchers.findByAssignedUserIdInAndStatusIn(anyList(), anyList(), any())).thenReturn(Page.empty());
        ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);

        controller.vouchers(PageRequest.of(0, 1000));

        verify(vouchers).findByAssignedUserIdInAndStatusIn(anyList(), eq(Voucher.LIVE_STATUSES), asked.capture());
        assertThat(asked.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void vouchers_withNoPhoneClaim_isRefused_andReadsNothing() {
        authenticateAs("  ");

        assertThatThrownBy(() -> controller.vouchers(PageRequest.of(0, 20)))
                .isInstanceOf(LoyaltyException.class)
                .hasMessage("/me endpoints require a JWT with a phoneNumber claim");
        verify(users, never()).findByPhoneNumber(anyString());
        verify(vouchers, never()).findByAssignedUserIdInAndStatusIn(anyList(), anyList(), any());
    }
}
