package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.dto.SupportDtos;
import com.innbucks.loyaltyservice.entity.SupportActivity;
import com.innbucks.loyaltyservice.security.SupportPermissions;
import com.innbucks.loyaltyservice.service.SupportActivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.FORBIDDEN;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.INVALID_RANGE;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.UNAUTHORIZED;

/**
 * The supervisor's view across every agent: what was looked at and done.
 * {@code loyalty-support:supervise} only.
 */
@RestController
@RequestMapping("/loyalty/support")
@Tag(name = "Customer Support — Oversight",
        description = "Every agent's activity, newest first. loyalty-support:supervise only.")
public class SupportOversightController {

    private final SupportActivityService activity;

    public SupportOversightController(SupportActivityService activity) {
        this.activity = activity;
    }

    @GetMapping("/activity")
    @PreAuthorize(SupportPermissions.HAS_SUPERVISE)
    @Operation(summary = "The support activity feed across all agents",
            description = """
                    Every lookup, view and action, newest first. Filters are optional and combine: \
                    `agentUuid` (the agent's userUuid), `action`, and an ISO-8601 `from` (inclusive) / \
                    `to` (exclusive). Phones are masked; `detail` carries ids, enums and amounts only — \
                    never free text. A reason an agent typed lives where the action keeps it (the \
                    transaction reference, or an internal note named by `noteId`).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of activity",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "OK",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "5e4d3c2b-1a0f-4e9d-8c7b-6a5f4e3d2c1b",
                                            "agent": {
                                              "uuid": "5b0e7a1c-3f2d-4c9e-8a7b-6d5e4f3a2b1c",
                                              "login": "agent.moyo@example.com"
                                            },
                                            "action": "POINTS_ADJUSTED",
                                            "subjectKind": "PHONE",
                                            "subjectId": "****4567",
                                            "detail": {
                                              "lookupId": "0c9b8a7d-6e5f-4a3b-9c2d-1e0f9a8b7c6d",
                                              "transactionId": "33333333-4444-5555-6666-777777777777",
                                              "userId": "8f14e45f-ceea-467a-9ba6-7c3f0e2a1b44",
                                              "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                              "points": 250
                                            },
                                            "createdAt": "2026-09-30T08:15:00Z"
                                          },
                                          {
                                            "id": "4d3c2b1a-0f9e-4d8c-7b6a-5f4e3d2c1b0a",
                                            "agent": {
                                              "uuid": "5b0e7a1c-3f2d-4c9e-8a7b-6d5e4f3a2b1c",
                                              "login": "agent.moyo@example.com"
                                            },
                                            "action": "SEARCH",
                                            "subjectKind": null,
                                            "subjectId": null,
                                            "detail": { "phone": "****9012", "found": false },
                                            "createdAt": "2026-09-30T08:01:00Z"
                                          }
                                        ],
                                        "page": 0, "size": 20, "totalElements": 2, "totalPages": 1,
                                        "first": true, "last": true
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "An unknown action, a malformed instant, or from not before to",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Inverted range", value = INVALID_RANGE),
                            @ExampleObject(name = "Unknown action", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Invalid value for 'action'. Accepted values: SEARCH, CUSTOMER_LOOKUP, VIEW_CUSTOMER, VIEW_TRANSACTIONS, VIEW_LEDGER, VIEW_VOUCHERS, VIEW_VOUCHER_ORDERS, VIEW_NOTES, VIEW_MESSAGES, NOTE_ADDED, MESSAGE_SENT, SESSIONS_REVOKED, POINTS_ADJUSTED, TRANSACTION_REVERSED, MEMBERSHIP_UNBLOCKED.",
                                      "data": null
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks loyalty-support:supervise",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN)))
    })
    public ResponseEntity<ApiResult<PageResponse<SupportDtos.ActivityResponse>>> activity(
            @Parameter(description = "Only this agent (their userUuid)") @RequestParam(required = false) String agentUuid,
            @Parameter(description = "Only this action") @RequestParam(required = false) SupportActivity.Action action,
            @Parameter(description = "ISO-8601 instant, inclusive") @RequestParam(required = false) Instant from,
            @Parameter(description = "ISO-8601 instant, exclusive") @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return SupportCustomerController.noStore(ApiResult.ok(
                activity.feed(agentUuid, action, from, to, page, size)));
    }
}
