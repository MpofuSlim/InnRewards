package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.dto.SupportDtos;
import com.innbucks.loyaltyservice.security.SupportAgent;
import com.innbucks.loyaltyservice.security.SupportPermissions;
import com.innbucks.loyaltyservice.service.SupportMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.BAD_PAGE_PARAM;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.CHANNEL_UNAVAILABLE;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.FORBIDDEN;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.LOOKUP_NOT_FOUND;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.MESSAGE_SENT;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.NOT_DELIVERED;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.RATE_LIMITED;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.RATE_LIMITED_AGENT;
import static com.innbucks.loyaltyservice.controller.SupportSwaggerExamples.UNAUTHORIZED;

/**
 * Record-bound customer messaging: an agent types an SMS/WhatsApp to the
 * customer of the lookup they hold. There is no recipient field anywhere — the
 * destination is the looked-up phone, resolved server-side — so an agent can
 * never message a number they typed (owner decision, 2026-09-30).
 */
@RestController
@RequestMapping("/loyalty/support/lookups/{lookupId}/messages")
@Tag(name = "Customer Support — Messages",
        description = "Type and send an SMS/WhatsApp to the looked-up customer (customer-messages:send), and "
                + "read the customer's support-message history (loyalty-support:read).")
public class SupportMessageController {

    private final SupportMessageService messages;

    public SupportMessageController(SupportMessageService messages) {
        this.messages = messages;
    }

    @PostMapping("/preview")
    @PreAuthorize(SupportPermissions.HAS_SEND_MESSAGES)
    @Operation(summary = "Preview a message: the exact text, its length and SMS segments",
            description = """
                    Typed "Your refund is done: see innbucks.co.zw/help" and asked for SMS, the example \
                    below is what the customer's phone shows.

                    Returns what the customer would receive on the channel's FIRST leg on this cell: for SMS \
                    and SMS_THEN_WHATSAPP (when SMS is provisioned), the text after GSM transliteration (`transliterated` says whether \
                    the sanitiser changed anything — it turns `:` and `/` into spaces, so a full URL does \
                    not survive SMS); for WHATSAPP, the text as typed. The signature is included.

                    Writes nothing and consumes no limit. It does NOT refuse an over-long text — it \
                    reports `characters` against `maxCharacters` so the screen can show the overrun; the \
                    send refuses it. A disallowed link or an empty body is refused here exactly as on send.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The preview",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "OK",
                                      "data": {
                                        "channel": "SMS",
                                        "recipientRole": "CUSTOMER",
                                        "recipient": "****4567",
                                        "text": "Your refund is done see innbucks.co.zw help\\n- InnBucks Loyalty Support",
                                        "characters": 70,
                                        "maxCharacters": 459,
                                        "smsSegments": 1,
                                        "transliterated": true
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Empty body, or a link to a host not on the allow-list",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Link not allowed", value = """
                                    {
                                      "code": "link_not_allowed",
                                      "message": "Links in a support message may only point at innbucks.co.zw - 'evil.example' is not one of them. If it is not meant as a link, add a space after the full stop.",
                                      "data": { "host": "evil.example" }
                                    }"""),
                            @ExampleObject(name = "Empty", value = """
                                    {
                                      "code": "invalid_message_body",
                                      "message": "Type a message. It is empty once formatting is removed.",
                                      "data": null
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks customer-messages:send",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Lookup expired or not yours",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOOKUP_NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<SupportDtos.PreviewResponse>> preview(
            @PathVariable UUID lookupId, @Valid @RequestBody SupportDtos.MessageRequest body) {
        return SupportCustomerController.noStore(ApiResult.ok(
                messages.preview(SupportAgent.current(), lookupId, body.channel(), body.body())));
    }

    @PostMapping
    @PreAuthorize(SupportPermissions.HAS_SEND_MESSAGES)
    @Operation(summary = "Send a typed message to the looked-up customer",
            description = """
                    The recipient is ALWAYS the looked-up customer's phone. The body is HTML-stripped, \
                    checked for links (each must point at an allowed host or a subdomain of one), \
                    signed on a new line, and capped on its FINAL text: 459 characters for SMS (three \
                    GSM-7 segments, after transliteration), 1000 for WhatsApp, both for SMS_THEN_WHATSAPP. \
                    A link is any scheme://, www., IPv4 address, bare name.tld or e-mail domain — so a \
                    missing space after a full stop ("Thanks.Your") reads as one; the refusal says so.

                    Order: validate (503 channel_unavailable before anything is written) -> a PENDING row \
                    claims the rate-limit slot (60 per agent per rolling hour, 5 per customer per rolling \
                    24h, every attempt and every kind counted) -> the gateway is called outside any \
                    transaction -> the row is completed. SMS_THEN_WHATSAPP uses whichever channels this \
                    cell has, in order: SMS when SMS is provisioned, then WhatsApp (after a failed SMS, or \
                    directly when SMS is not provisioned). All attempted channels failing is a 502 whose \
                    data is the FAILED record (failureCode sms_failed / whatsapp_failed / \
                    sms_and_whatsapp_failed, naming what was tried). Writes a MESSAGE_SENT activity row \
                    either way.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Sent",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = MESSAGE_SENT))),
            @ApiResponse(responseCode = "400", description = "Empty body, a disallowed link, a text over the channel's cap, or a missing field",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Link not allowed", value = """
                                    {
                                      "code": "link_not_allowed",
                                      "message": "Links in a support message may only point at innbucks.co.zw - 'evil.example' is not one of them. If it is not meant as a link, add a space after the full stop.",
                                      "data": { "host": "evil.example" }
                                    }"""),
                            @ExampleObject(name = "Too long", value = """
                                    {
                                      "code": "message_too_long",
                                      "message": "The SMS text is 512 characters; the limit is 459.",
                                      "data": { "channel": "SMS", "characters": 512, "maxCharacters": 459 }
                                    }"""),
                            @ExampleObject(name = "Empty", value = """
                                    {
                                      "code": "invalid_message_body",
                                      "message": "Type a message. It is empty once formatting is removed.",
                                      "data": null
                                    }"""),
                            @ExampleObject(name = "Missing channel", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Validation failed",
                                      "data": { "channel": "must not be null" }
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks customer-messages:send",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Lookup expired or not yours",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOOKUP_NOT_FOUND))),
            @ApiResponse(responseCode = "429", description = "Over the per-customer or per-agent limit; data names which",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Customer", value = RATE_LIMITED),
                            @ExampleObject(name = "Agent", value = RATE_LIMITED_AGENT)})),
            @ApiResponse(responseCode = "502", description = "Every channel failed; the FAILED record is in data",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_DELIVERED))),
            @ApiResponse(responseCode = "503", description = "The requested channel is not provisioned on this cell (for SMS_THEN_WHATSAPP: neither is); nothing written",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = CHANNEL_UNAVAILABLE)))
    })
    public ResponseEntity<ApiResult<SupportDtos.MessageResponse>> send(
            @PathVariable UUID lookupId, @Valid @RequestBody SupportDtos.MessageRequest body) {
        SupportDtos.MessageResponse sent = messages.send(SupportAgent.current(), lookupId, body.channel(), body.body());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResult.created("Message sent", sent));
    }

    @GetMapping
    @PreAuthorize(SupportPermissions.HAS_READ)
    @Operation(summary = "Support messages sent to the customer, newest first",
            description = "Every support message to this phone by any agent, typed or voucher resend. A "
                    + "voucher resend's `text` is always null (it carries the code). Writes a VIEW_MESSAGES "
                    + "activity row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of messages",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiResult.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "OK",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "0f9e8d7c-6b5a-4f4e-9d3c-2b1a0f9e8d7c",
                                            "kind": "VOUCHER_RESEND",
                                            "channelRequested": "WHATSAPP",
                                            "deliveredVia": "WHATSAPP",
                                            "outcome": "SENT",
                                            "recipientRole": "VOUCHER_HOLDER",
                                            "recipient": "****4567",
                                            "text": null,
                                            "sentBy": { "uuid": "5b0e7a1c-3f2d-4c9e-8a7b-6d5e4f3a2b1c", "login": "agent.moyo@example.com" },
                                            "createdAt": "2026-09-30T08:40:00Z",
                                            "completedAt": "2026-09-30T08:40:01Z",
                                            "failureCode": null
                                          }
                                        ],
                                        "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
                                        "first": true, "last": true
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "A page/size that is not a number",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = BAD_PAGE_PARAM))),
            @ApiResponse(responseCode = "401", description = "No or invalid token",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Token lacks loyalty-support:read",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Lookup expired or not yours",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = LOOKUP_NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<PageResponse<SupportDtos.MessageResponse>>> history(
            @PathVariable UUID lookupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return SupportCustomerController.noStore(ApiResult.ok(
                messages.forCustomer(SupportAgent.current(), lookupId, page, size)));
    }
}
