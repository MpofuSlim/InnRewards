package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.PartnershipDtos;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.VoucherPartnershipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/loyalty")
@Tag(name = "Voucher partnerships",
        description = "Two tenants that accept each other's vouchers (V68). A tenant invites another by its code, "
                + "the other accepts or declines, either side can end it, and each side chooses which of its "
                + "merchants take part. A partner voucher sold with redeemableAt \"ANY\", or naming the other "
                + "side's merchants, then also works there. "
                + "Tenant calls need X-Tenant-Id; a SUPER_ADMIN acts for any tenant by sending its id.")
public class VoucherPartnershipController {

    static final String WRITERS =
            "hasAnyRole('MERCHANT_ADMIN','TENANT_ADMIN','PLATFORM_ADMIN','SUPER_ADMIN')";

    private final VoucherPartnershipService service;
    private final TenantContext tenantContext;

    public VoucherPartnershipController(VoucherPartnershipService service, TenantContext tenantContext) {
        this.service = service;
        this.tenantContext = tenantContext;
    }

    // ---------------------------------------------------------------- examples

    static final String VIEW_200 = """
            {
              "code": "200 OK",
              "message": "Partnership",
              "data": {
                "id": "3f1e9a6c-6d1b-4b5e-9a3e-1f2d3c4b5a69",
                "status": "ACTIVE",
                "autoAccepted": false,
                "inviter": {
                  "tenantId": "7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f",
                  "tenantCode": "mwos",
                  "tenantName": "MWOS",
                  "merchants": [ { "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789", "name": "MWOS" } ]
                },
                "invitee": {
                  "tenantId": "9c2d4e6f-1a3b-4c5d-8e7f-0a1b2c3d4e60",
                  "tenantCode": "bolabet",
                  "tenantName": "Bola Bet",
                  "merchants": [ { "merchantId": "c5d1e3f4-3456-789a-bcde-f0123456789a", "name": "Bola Bet" } ]
                },
                "invitedAt": "2026-11-12T08:00:00Z",
                "decidedAt": "2026-11-12T09:30:00Z",
                "endedAt": null,
                "suspendedAt": null,
                "suspensionReason": null,
                "invitedBy": null,
                "decidedBy": null,
                "endedBy": null,
                "suspendedBy": null
              }
            }""";

    static final String LIST_200 = """
            {
              "code": "200 OK",
              "message": "Partnerships",
              "data": [
                {
                  "id": "3f1e9a6c-6d1b-4b5e-9a3e-1f2d3c4b5a69",
                  "status": "INVITED",
                  "autoAccepted": false,
                  "inviter": { "tenantId": "7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f", "tenantCode": "mwos",
                               "tenantName": "MWOS",
                               "merchants": [ { "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789", "name": "MWOS" } ] },
                  "invitee": { "tenantId": "9c2d4e6f-1a3b-4c5d-8e7f-0a1b2c3d4e60", "tenantCode": "bolabet",
                               "tenantName": "Bola Bet", "merchants": [] },
                  "invitedAt": "2026-11-12T08:00:00Z",
                  "decidedAt": null, "endedAt": null, "suspendedAt": null, "suspensionReason": null,
                  "invitedBy": null, "decidedBy": null, "endedBy": null, "suspendedBy": null
                }
              ]
            }""";

    static final String VALIDATION_400 = """
            {
              "code": "400 BAD_REQUEST",
              "message": "Validation failed",
              "data": { "merchantIds": "must not be empty" }
            }""";

    static final String SELF_400 = """
            {
              "code": "PARTNERSHIP_SELF",
              "message": "A tenant can't partner with itself.",
              "data": null
            }""";

    static final String INACTIVE_400 = """
            {
              "code": "MERCHANT_INACTIVE",
              "message": "MWOS Avondale is not active.",
              "data": null
            }""";

    static final String NOT_OWNER_403 = """
            {
              "code": "NOT_MERCHANT_OWNER",
              "message": "You can only act on merchants you administer.",
              "data": null
            }""";

    static final String NOT_INVITEE_403 = """
            {
              "code": "NOT_INVITED_TENANT",
              "message": "Only the invited tenant can accept or decline.",
              "data": null
            }""";

    static final String TENANT_404 = """
            {
              "code": "404 NOT_FOUND",
              "message": "tenant not found",
              "data": null
            }""";

    static final String PARTNERSHIP_404 = """
            {
              "code": "404 NOT_FOUND",
              "message": "partnership not found",
              "data": null
            }""";

    static final String EXISTS_409 = """
            {
              "code": "PARTNERSHIP_EXISTS",
              "message": "These two tenants already have a partnership. End it before starting another.",
              "data": null
            }""";

    static final String NOT_PENDING_409 = """
            {
              "code": "PARTNERSHIP_NOT_PENDING",
              "message": "This partnership is not waiting for an answer.",
              "data": null
            }""";

    static final String NOT_LIVE_409 = """
            {
              "code": "PARTNERSHIP_NOT_LIVE",
              "message": "This partnership has ended.",
              "data": null
            }""";

    static final String NOT_ANSWERED_409 = """
            {
              "code": "PARTNERSHIP_NOT_ANSWERED",
              "message": "Accept the invitation to choose your merchants.",
              "data": null
            }""";

    static final String NOT_ACTIVE_409 = """
            {
              "code": "PARTNERSHIP_NOT_ACTIVE",
              "message": "Only an active partnership can be suspended.",
              "data": null
            }""";

    static final String NOT_SUSPENDED_409 = """
            {
              "code": "PARTNERSHIP_NOT_SUSPENDED",
              "message": "This partnership is not suspended.",
              "data": null
            }""";

    // ------------------------------------------------------------------ reads

    @GetMapping("/partnerships")
    @PreAuthorize(WRITERS)
    @Operation(summary = "This tenant's partnerships, newest first",
            description = "Every partnership the tenant is on either side of, with each side's merchants. "
                    + "Who invited or answered is shown to InnBucks only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The partnerships",
                    content = @Content(examples = @ExampleObject(name = "One pending invitation", value = LIST_200)))
    })
    public ResponseEntity<ApiResult<List<PartnershipDtos.PartnershipView>>> list() {
        return ResponseEntity.ok(ApiResult.ok("Partnerships", service.list(tenantContext.requireTenantId())));
    }

    @GetMapping("/admin/partnerships")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Every partnership (SUPER_ADMIN)",
            description = "Across all tenants, newest first, with who invited, answered, ended or suspended each. "
                    + "No X-Tenant-Id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every partnership",
                    content = @Content(examples = @ExampleObject(name = "All", value = LIST_200)))
    })
    public ResponseEntity<ApiResult<List<PartnershipDtos.PartnershipView>>> listAll() {
        return ResponseEntity.ok(ApiResult.ok("Partnerships", service.listAll()));
    }

    // ----------------------------------------------------------------- writes

    @PostMapping("/partnerships")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Invite another tenant",
            description = "Names the other tenant by its code and your merchants that take part. The other "
                    + "tenant accepts with its own merchants. On a staging cell with "
                    + "LOYALTY_PARTNERSHIP_AUTO_ACCEPT on (simulated money only), it is active at once with every "
                    + "active merchant of the other tenant, marked autoAccepted. A merchant admin can put in only "
                    + "the merchants it administers.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Invited (or active at once on staging)",
                    content = @Content(examples = @ExampleObject(name = "Invited", value = VIEW_200))),
            @ApiResponse(responseCode = "400", description = "Bad request",
                    content = @Content(examples = {
                            @ExampleObject(name = "Validation", value = VALIDATION_400),
                            @ExampleObject(name = "Your own tenant", value = SELF_400),
                            @ExampleObject(name = "An inactive merchant", value = INACTIVE_400)})),
            @ApiResponse(responseCode = "403", description = "A merchant you don't administer",
                    content = @Content(examples = @ExampleObject(name = "Not yours", value = NOT_OWNER_403))),
            @ApiResponse(responseCode = "404", description = "No tenant has that code",
                    content = @Content(examples = @ExampleObject(name = "Unknown code", value = TENANT_404))),
            @ApiResponse(responseCode = "409", description = "Already partners, or invited",
                    content = @Content(examples = @ExampleObject(name = "Exists", value = EXISTS_409)))
    })
    public ResponseEntity<ApiResult<PartnershipDtos.PartnershipView>> invite(
            @Valid @RequestBody PartnershipDtos.InviteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Partnership invited", service.invite(tenantContext.requireTenantId(), req)));
    }

    @PostMapping("/partnerships/{id}/accept")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Accept an invitation, with your merchants",
            description = "The invited tenant only. From now, vouchers of the two sides' merchants may name each "
                    + "other's merchants.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active",
                    content = @Content(examples = @ExampleObject(name = "Active", value = VIEW_200))),
            @ApiResponse(responseCode = "400", description = "Bad request",
                    content = @Content(examples = {
                            @ExampleObject(name = "Validation", value = VALIDATION_400),
                            @ExampleObject(name = "An inactive merchant", value = INACTIVE_400)})),
            @ApiResponse(responseCode = "403", description = "Not the invited tenant, or not your merchant",
                    content = @Content(examples = {
                            @ExampleObject(name = "The inviter", value = NOT_INVITEE_403),
                            @ExampleObject(name = "Not yours", value = NOT_OWNER_403)})),
            @ApiResponse(responseCode = "404", description = "Not one of this tenant's partnerships",
                    content = @Content(examples = @ExampleObject(name = "Unknown", value = PARTNERSHIP_404))),
            @ApiResponse(responseCode = "409", description = "Already answered",
                    content = @Content(examples = @ExampleObject(name = "Answered", value = NOT_PENDING_409)))
    })
    public ResponseEntity<ApiResult<PartnershipDtos.PartnershipView>> accept(
            @PathVariable UUID id, @Valid @RequestBody PartnershipDtos.MerchantsRequest req) {
        return ResponseEntity.ok(ApiResult.ok("Partnership accepted",
                service.accept(tenantContext.requireTenantId(), id, req)));
    }

    @PostMapping("/partnerships/{id}/decline")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Decline an invitation", description = "The invited tenant only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Declined",
                    content = @Content(examples = @ExampleObject(name = "Declined", value = VIEW_200))),
            @ApiResponse(responseCode = "403", description = "Not the invited tenant",
                    content = @Content(examples = @ExampleObject(name = "The inviter", value = NOT_INVITEE_403))),
            @ApiResponse(responseCode = "404", description = "Not one of this tenant's partnerships",
                    content = @Content(examples = @ExampleObject(name = "Unknown", value = PARTNERSHIP_404))),
            @ApiResponse(responseCode = "409", description = "Already answered",
                    content = @Content(examples = @ExampleObject(name = "Answered", value = NOT_PENDING_409)))
    })
    public ResponseEntity<ApiResult<PartnershipDtos.PartnershipView>> decline(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResult.ok("Partnership declined",
                service.decline(tenantContext.requireTenantId(), id)));
    }

    @PutMapping("/partnerships/{id}/merchants")
    @PreAuthorize(WRITERS)
    @Operation(summary = "Change which of your merchants take part",
            description = "Replaces YOUR side only. Vouchers already sold keep the merchants they were sold with.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed",
                    content = @Content(examples = @ExampleObject(name = "Changed", value = VIEW_200))),
            @ApiResponse(responseCode = "400", description = "Bad request",
                    content = @Content(examples = {
                            @ExampleObject(name = "Validation", value = VALIDATION_400),
                            @ExampleObject(name = "An inactive merchant", value = INACTIVE_400)})),
            @ApiResponse(responseCode = "403", description = "Not your merchant",
                    content = @Content(examples = @ExampleObject(name = "Not yours", value = NOT_OWNER_403))),
            @ApiResponse(responseCode = "404", description = "Not one of this tenant's partnerships",
                    content = @Content(examples = @ExampleObject(name = "Unknown", value = PARTNERSHIP_404))),
            @ApiResponse(responseCode = "409", description = "Ended, or an invitation not yet answered",
                    content = @Content(examples = {
                            @ExampleObject(name = "Ended", value = NOT_LIVE_409),
                            @ExampleObject(name = "Answer first", value = NOT_ANSWERED_409)}))
    })
    public ResponseEntity<ApiResult<PartnershipDtos.PartnershipView>> setMerchants(
            @PathVariable UUID id, @Valid @RequestBody PartnershipDtos.MerchantsRequest req) {
        return ResponseEntity.ok(ApiResult.ok("Partnership merchants changed",
                service.setMerchants(tenantContext.requireTenantId(), id, req)));
    }

    @PostMapping("/partnerships/{id}/end")
    @PreAuthorize(WRITERS)
    @Operation(summary = "End a partnership (either side)",
            description = "No new voucher can name the other side's merchants. Vouchers already sold stay usable "
                    + "where they were sold for until they expire. Ending an unanswered invitation withdraws it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ended",
                    content = @Content(examples = @ExampleObject(name = "Ended", value = VIEW_200))),
            @ApiResponse(responseCode = "404", description = "Not one of this tenant's partnerships",
                    content = @Content(examples = @ExampleObject(name = "Unknown", value = PARTNERSHIP_404))),
            @ApiResponse(responseCode = "409", description = "Already over",
                    content = @Content(examples = @ExampleObject(name = "Ended", value = NOT_LIVE_409)))
    })
    public ResponseEntity<ApiResult<PartnershipDtos.PartnershipView>> end(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResult.ok("Partnership ended", service.end(tenantContext.requireTenantId(), id)));
    }

    @PostMapping("/admin/partnerships/{id}/suspend")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Suspend a partnership (SUPER_ADMIN)",
            description = "Stops it at once: no new voucher names the other side, and no voucher is used across "
                    + "it, even one already sold, until it is reinstated. No X-Tenant-Id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Suspended",
                    content = @Content(examples = @ExampleObject(name = "Suspended", value = VIEW_200))),
            @ApiResponse(responseCode = "400", description = "No reason",
                    content = @Content(examples = @ExampleObject(name = "Validation", value = """
                            {
                              "code": "400 BAD_REQUEST",
                              "message": "Validation failed",
                              "data": { "reason": "must not be blank" }
                            }"""))),
            @ApiResponse(responseCode = "404", description = "Unknown partnership",
                    content = @Content(examples = @ExampleObject(name = "Unknown", value = PARTNERSHIP_404))),
            @ApiResponse(responseCode = "409", description = "Not active",
                    content = @Content(examples = @ExampleObject(name = "Not active", value = NOT_ACTIVE_409)))
    })
    public ResponseEntity<ApiResult<PartnershipDtos.PartnershipView>> suspend(
            @PathVariable UUID id, @Valid @RequestBody PartnershipDtos.SuspendRequest req) {
        return ResponseEntity.ok(ApiResult.ok("Partnership suspended", service.suspend(id, req)));
    }

    @PostMapping("/admin/partnerships/{id}/reinstate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Reinstate a suspended partnership (SUPER_ADMIN)", description = "No X-Tenant-Id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active again",
                    content = @Content(examples = @ExampleObject(name = "Active", value = VIEW_200))),
            @ApiResponse(responseCode = "404", description = "Unknown partnership",
                    content = @Content(examples = @ExampleObject(name = "Unknown", value = PARTNERSHIP_404))),
            @ApiResponse(responseCode = "409", description = "Not suspended",
                    content = @Content(examples = @ExampleObject(name = "Not suspended", value = NOT_SUSPENDED_409)))
    })
    public ResponseEntity<ApiResult<PartnershipDtos.PartnershipView>> reinstate(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResult.ok("Partnership reinstated", service.reinstate(id)));
    }
}
