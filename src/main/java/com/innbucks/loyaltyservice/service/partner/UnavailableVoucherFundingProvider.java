package com.innbucks.loyaltyservice.service.partner;

/**
 * No funding provider configured ({@code loyalty.voucher-partner.funding: NONE}):
 * a generate is refused before anything is recorded, and nothing is ever moved.
 * The default until the BR or Veengu integration is built.
 */
public class UnavailableVoucherFundingProvider implements VoucherFundingProvider {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public boolean simulated() {
        return false;
    }

    @Override
    public FundingOutcome fund(FundingLeg leg) {
        return FundingOutcome.notSent();
    }

    @Override
    public FundingOutcome status(FundingLeg leg) {
        return FundingOutcome.pending();
    }

    @Override
    public SettlementOutcome settle(SettlementLeg leg) {
        return SettlementOutcome.notYet();
    }
}
