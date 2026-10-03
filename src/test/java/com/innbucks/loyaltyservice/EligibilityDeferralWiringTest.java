package com.innbucks.loyaltyservice;

import com.innbucks.loyaltyservice.testsupport.SpendGateCallGraph;
import fixtures.eligibility.SpendCallShapes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every production entry point that reaches the spend gate
 * ({@code UserService.spendabilityOf}) must run it inside
 * {@code EligibilityDeferral.run} — otherwise the V44 on-demand eligibility
 * check goes back to running its HTTP call inside the spend transaction (and,
 * for a voucher, under the voucher's row lock).
 *
 * <p>Read from the bytecode ({@link SpendGateCallGraph}), so it needs no list
 * of "spend endpoints" to keep current: a new controller method, a new service
 * method that reaches the gate, or a new caller of an existing one is found by
 * the call graph itself.
 */
class EligibilityDeferralWiringTest {

    @Test
    void everyControllerCallThatReachesTheSpendGate_goesThroughTheDeferral() throws Exception {
        SpendGateCallGraph graph = SpendGateCallGraph.ofMainClasses();

        assertThat(graph.unwrappedControllerCalls())
                .as("controller calls that reach UserService.spendabilityOf without EligibilityDeferral.run "
                        + "— wrap them: eligibilityDeferral.run(() -> service.call(...))")
                .isEmpty();
    }

    @Test
    void theGraphActuallySeesTheSpendPaths_soAnEmptyResultAboveIsNotVacuous() throws Exception {
        SpendGateCallGraph graph = SpendGateCallGraph.ofMainClasses();
        String svc = "com/innbucks/loyaltyservice/service/";

        // The three gates and every service path into them that exists today.
        assertThat(graph.reachesGate(svc + "UserService", "requireSpendable")).isTrue();
        assertThat(graph.reachesGate(svc + "RedemptionService", "redeemPoints")).isTrue();
        assertThat(graph.reachesGate(svc + "RedemptionService", "redeemPointsIdempotent")).isTrue();
        assertThat(graph.reachesGate(svc + "TransferService", "transfer")).isTrue();
        assertThat(graph.reachesGate(svc + "VoucherService", "redeem")).isTrue();
        assertThat(graph.reachesGate(svc + "QrService", "consume")).isTrue();
        assertThat(graph.reachesGate(svc + "ShopCheckoutService", "checkout")).isTrue();
        assertThat(graph.reachesGate(svc + "TicketingLoyaltyService", "redeem")).isTrue();
        // ...and not everything: an earn is not a spend.
        assertThat(graph.reachesGate(svc + "TransactionService", "post")).isFalse();

        assertThat(graph.controllersWithWrappedSpends()).contains(
                "TransactionController", "VoucherController", "QrController", "ShopController",
                "InternalMerchantLookupController", "PublicTestController");
    }

    @Test
    void noScheduledJobOrEventListenerReachesTheGate_sinceNothingWouldWrapIt() throws Exception {
        assertThat(SpendGateCallGraph.ofMainClasses().unwrappedEntryPoints()).isEmpty();
    }

    @Test
    void theDetectorFlagsAnUnwrappedCall_andAcceptsAWrappedOne() throws Exception {
        SpendGateCallGraph graph = SpendGateCallGraph.ofMainClasses().addClass(SpendCallShapes.class, true);

        assertThat(graph.unwrappedControllerCalls())
                .filteredOn(s -> s.startsWith("SpendCallShapes."))
                .hasSize(2)
                .anyMatch(s -> s.startsWith("SpendCallShapes.unwrappedDirect -> TransferService.transfer"))
                // the lambda handed to something other than run() is still unwrapped
                .anyMatch(s -> s.startsWith("SpendCallShapes.lambda$unwrappedInsideSomeOtherLambda")
                        && s.endsWith("-> VoucherService.redeem"));
    }
}
