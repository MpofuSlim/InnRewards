package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.exception.GlobalExceptionHandler;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import com.innbucks.loyaltyservice.security.PublicTestApiKeyFilter;
import com.innbucks.loyaltyservice.service.EligibilityDeferral;
import com.innbucks.loyaltyservice.service.OnDemandEligibilityCheck;
import com.innbucks.loyaltyservice.service.RedemptionService;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.TransferService;
import com.innbucks.loyaltyservice.service.UserService;
import com.innbucks.loyaltyservice.service.VoucherService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A switched-off {@code /loyalty/public/**} must look like a path that does not
 * exist, whatever the request holds. Reported by the super app team: with the
 * surface off, {@code POST /loyalty/public/vouchers/redeem} with an empty body
 * answered 400, because Spring reads the body before the controller's own 404
 * check runs. That told a caller the route exists and what it expects.
 *
 * <p>Real dispatch (standalone MockMvc, the real controller, filter and advice),
 * because the defect was in the ORDER Spring runs things, which a unit test of
 * either class cannot see.
 */
class PublicTestSurfaceOffDispatchTest {

    private static final String UNKNOWN_PATH_404 = "{\"code\":\"404 NOT_FOUND\",\"message\":\""
            + GlobalExceptionHandler.NOT_FOUND_MESSAGE + "\",\"data\":null}";

    private final LoyaltyUserRepository users = mock(LoyaltyUserRepository.class);
    private final VoucherService voucherService = mock(VoucherService.class);
    private final VoucherRepository vouchers = mock(VoucherRepository.class);

    private MockMvc mvc(boolean withFilter) {
        PublicTestController controller = new PublicTestController(users, mock(WalletRepository.class),
                vouchers, mock(MerchantRepository.class), mock(TransactionService.class),
                mock(TransferService.class), mock(RedemptionService.class), voucherService,
                new EligibilityDeferral(mock(OnDemandEligibilityCheck.class), mock(UserService.class)));
        ReflectionTestUtils.setField(controller, "enabled", false);
        StandaloneMockMvcBuilder builder = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler());
        if (withFilter) {
            builder.addFilters(new PublicTestApiKeyFilter(false, "", new LoyaltyMetrics(new SimpleMeterRegistry())));
        }
        return builder.build();
    }

    @Test
    void withoutTheFilter_anEmptyBodyIs400_whichIsWhatTheFeReported() throws Exception {
        mvc(false).perform(post("/loyalty/public/vouchers/redeem")
                        .contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void surfaceOff_everyShapeOfRequest_isTheUnknownPath404() throws Exception {
        MockMvc mvc = mvc(true);
        MockHttpServletRequestBuilder[] requests = {
                // The FE's case: an empty body.
                post("/loyalty/public/vouchers/redeem").contentType(MediaType.APPLICATION_JSON).content(""),
                // A body that fails validation, and one that is not JSON.
                post("/loyalty/public/vouchers/redeem").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/loyalty/public/vouchers/redeem").contentType(MediaType.APPLICATION_JSON).content("nope"),
                // A path variable that is not a UUID.
                post("/loyalty/public/vouchers/not-a-uuid/transfer")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                // The wrong method, which was a 405 with an Allow header.
                delete("/loyalty/public/vouchers/redeem"),
                // A proper read, and a path that never existed under the prefix.
                get("/loyalty/public/customers/+263770000000/wallet"),
                get("/loyalty/public/nothing-here"),
        };
        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(content().string(UNKNOWN_PATH_404))
                    .andExpect(header().doesNotExist("Allow"));
        }
        verifyNoInteractions(users, vouchers, voucherService);
    }
}
