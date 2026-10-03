package fixtures.eligibility;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.service.EligibilityDeferral;
import com.innbucks.loyaltyservice.service.TransferService;
import com.innbucks.loyaltyservice.service.VoucherService;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bytecode fixture for {@code EligibilityDeferralWiringTest}: the call shapes a
 * controller might use, so the test can prove its detector tells them apart.
 * Deliberately NOT a Spring bean and outside the application's scan package —
 * it is only ever read as bytecode, never instantiated.
 */
public class SpendCallShapes {

    private final EligibilityDeferral deferral;
    private final TransferService transfers;
    private final VoucherService vouchers;

    public SpendCallShapes(EligibilityDeferral deferral, TransferService transfers, VoucherService vouchers) {
        this.deferral = deferral;
        this.transfers = transfers;
        this.vouchers = vouchers;
    }

    public BigDecimal wrapped(UUID tenantId, Dtos.TransferRequest req) {
        return deferral.run(() -> transfers.transfer(tenantId, req));
    }

    public BigDecimal unwrappedDirect(UUID tenantId, Dtos.TransferRequest req) {
        return transfers.transfer(tenantId, req);
    }

    public Dtos.RedemptionResponse unwrappedInsideSomeOtherLambda(UUID t, UUID m, Dtos.RedeemVoucherRequest r) {
        return around(() -> vouchers.redeem(t, m, r));
    }

    public Dtos.RedemptionResponse wrappedInsideAnotherLambda(UUID t, UUID m, Dtos.RedeemVoucherRequest r) {
        return around(() -> deferral.run(() -> vouchers.redeem(t, m, r)));
    }

    public Dtos.VoucherResponse notASpend(UUID t, UUID id, Dtos.VoucherTransferRequest r) {
        return vouchers.transfer(t, id, r);
    }

    private static <T> T around(Supplier<T> work) {
        return work.get();
    }
}
