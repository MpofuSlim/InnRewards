package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.MiniAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/loyalty/mini-apps")
@Tag(name = "Mini-apps",
     description = "SuperApp shell metadata. The SuperApp client renders a list of mini-apps (merchant " +
                   "storefronts, sub-experiences) and uses each manifest's `entryUrl` and icon to launch " +
                   "the embedded experience. Requires X-Tenant-Id.")
public class MiniAppController {

    private final MiniAppService miniApps;
    private final TenantContext tenantContext;

    public MiniAppController(MiniAppService miniApps, TenantContext tenantContext) {
        this.miniApps = miniApps;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/manifest")
    @Operation(summary = "List mini-app manifests",
            description = "Returns every mini-app available to the current tenant, optionally filtered to a " +
                          "single merchant via the `merchantId` query param. Each manifest carries the " +
                          "entry URL, icon, and human-readable name the SuperApp shell renders on the home " +
                          "screen.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Mini-app manifests returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(name = "Paginated manifests", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Mini-app manifests retrieved successfully",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "ma1a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8",
                                            "slug": "innbucks-coffee",
                                            "name": "Innbucks Coffee",
                                            "description": "Order your favourite brew, earn points on every cup.",
                                            "iconUrl": "https://cdn.innbucks.example.com/mini-apps/coffee.png",
                                            "entryUrl": "https://miniapps.innbucks.example.com/coffee/index.html"
                                          },
                                          {
                                            "id": "ma2b3c4d-5e6f-7081-9293-a4b5c6d7e8f9",
                                            "slug": "acme-rewards",
                                            "name": "Acme Rewards",
                                            "description": "Track Acme loyalty perks and redeem vouchers.",
                                            "iconUrl": "https://cdn.innbucks.example.com/mini-apps/acme.png",
                                            "entryUrl": "https://miniapps.innbucks.example.com/acme/index.html"
                                          }
                                        ],
                                        "page": 0,
                                        "size": 20,
                                        "totalElements": 2,
                                        "totalPages": 1,
                                        "first": true,
                                        "last": true
                                      }
                                    }
                                    """)
                    )
            )
    })
    @PreAuthorize("hasAnyRole('CUSTOMER','MERCHANT_ADMIN','SHOP_ADMIN','EVENT_ORGANIZER','SUPER_ADMIN')")
    public ResponseEntity<ApiResult<PageResponse<Dtos.MiniAppManifest>>> manifest(
            @RequestParam(required = false) UUID merchantId,
            @ParameterObject Pageable pageable) {
        PageResponse<Dtos.MiniAppManifest> data = PageResponse.from(
                miniApps.manifest(tenantContext.requireTenantId(), merchantId), pageable);
        return ResponseEntity.ok(ApiResult.ok("Mini-app manifests retrieved successfully", data));
    }
}
