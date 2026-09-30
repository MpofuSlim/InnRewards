package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.dto.SupportDtos;
import com.innbucks.loyaltyservice.entity.SupportActivity;
import com.innbucks.loyaltyservice.entity.SupportNote;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.SupportNoteRepository;
import com.innbucks.loyaltyservice.security.SupportAgent;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal notes on a customer — the case-management half of support (owner
 * decision: notes + message log + activity log, no tickets). Append-only: there
 * is no edit and no delete anywhere, because a support log that can be
 * rewritten proves nothing.
 *
 * <p>The subject is the looked-up phone ({@code subject_kind = PHONE}), so a
 * note follows the customer across every tenant and every agent.
 */
@Service
public class SupportNoteService {

    private final SupportNoteRepository notes;
    private final SupportCustomerService customers;
    private final SupportActivityService activity;

    public SupportNoteService(SupportNoteRepository notes, SupportCustomerService customers,
                              SupportActivityService activity) {
        this.notes = notes;
        this.customers = customers;
        this.activity = activity;
    }

    @Transactional
    public SupportDtos.NoteResponse add(SupportAgent agent, UUID lookupId, String rawBody) {
        SupportCustomerService.Customer customer = customers.resolve(agent, lookupId);
        SupportNote note = write(agent, customer.phone(), cleanBody(rawBody));
        activity.record(agent, SupportActivity.Action.NOTE_ADDED, SupportActivity.SUBJECT_PHONE, customer.phone(),
                SupportActivityService.detail("lookupId", lookupId, "noteId", note.getId()));
        return toResponse(note);
    }

    /**
     * A note the SERVICE writes on the agent's behalf, recording the reason for
     * an action whose own tables have nowhere to keep one (a sign-out, an
     * unblock). Joins the action's transaction, so it exists iff the action does.
     * The caller writes the action's activity row, naming this note's id.
     */
    @Transactional
    public SupportNote recordActionReason(SupportAgent agent, String phone, String text) {
        String body = text.length() > SupportNote.MAX_BODY ? text.substring(0, SupportNote.MAX_BODY) : text;
        return write(agent, phone, body);
    }

    @Transactional
    public PageResponse<SupportDtos.NoteResponse> list(SupportAgent agent, UUID lookupId, int page, int size) {
        SupportCustomerService.Customer customer = customers.resolve(agent, lookupId);
        Pageable pageable = PageRequest.of(SupportPaging.page(page), SupportPaging.size(size));
        customers.logView(agent, customer, SupportActivity.Action.VIEW_NOTES,
                "page", pageable.getPageNumber(), "size", pageable.getPageSize());
        return PageResponse.from(notes.findBySubjectKindAndSubjectIdOrderByCreatedAtDesc(
                SupportActivity.SUBJECT_PHONE, customer.phone(), pageable).map(SupportNoteService::toResponse));
    }

    /** HTML stripped (stored-XSS hardening, like every free-text write here), trimmed, then 1..2000. */
    static String cleanBody(String raw) {
        String body = raw == null ? "" : HtmlSanitizer.stripAll(raw).strip();
        if (body.isEmpty() || body.length() > SupportNote.MAX_BODY) {
            throw LoyaltyException.badRequest("invalid_note_body",
                    "A note must be 1 to " + SupportNote.MAX_BODY + " characters once formatting is removed.");
        }
        return body;
    }

    private SupportNote write(SupportAgent agent, String phone, String body) {
        SupportNote note = new SupportNote();
        note.setSubjectKind(SupportActivity.SUBJECT_PHONE);
        note.setSubjectId(phone);
        note.setBody(body);
        note.setAgentUuid(agent.uuid());
        note.setAgentLogin(agent.login());
        note.setCreatedAt(Instant.now());
        return notes.save(note);
    }

    static SupportDtos.NoteResponse toResponse(SupportNote n) {
        return new SupportDtos.NoteResponse(n.getId(), n.getSubjectKind(),
                SupportActivityService.maskSubject(n.getSubjectKind(), n.getSubjectId()),
                n.getBody(), new SupportDtos.AgentRef(n.getAgentUuid(), n.getAgentLogin()), n.getCreatedAt());
    }
}
