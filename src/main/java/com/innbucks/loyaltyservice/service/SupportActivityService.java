package com.innbucks.loyaltyservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.loyaltyservice.config.SupportProperties;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.dto.SupportDtos;
import com.innbucks.loyaltyservice.entity.SupportActivity;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.SupportActivityRepository;
import com.innbucks.loyaltyservice.security.SupportAgent;
import com.innbucks.loyaltyservice.util.MsisdnMasking;
import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The support oversight record ({@code support_activity}, V55) and the lookup
 * sessions that ride on it.
 *
 * <p>There is no tamper-evident audit chain in this service (marketplace and
 * user-service have one; porting it is a separate item). Until then this table
 * is THE record of who looked at which customer and what they did: append-only
 * in code, written in the same transaction as the action it describes, so an
 * action that rolled back leaves no row claiming it happened.
 */
@Service
@Slf4j
public class SupportActivityService {

    /** Column width of {@code support_activity.detail}. */
    static final int MAX_DETAIL = 500;

    private static final TypeReference<LinkedHashMap<String, Object>> DETAIL_TYPE = new TypeReference<>() {};

    private final SupportActivityRepository activities;
    private final ObjectMapper json;
    private final SupportProperties props;

    public SupportActivityService(SupportActivityRepository activities, ObjectMapper json,
                                  SupportProperties props) {
        this.activities = activities;
        this.json = json;
        this.props = props;
    }

    /**
     * Writes one row. Joins the caller's transaction when there is one, so the
     * row commits (or rolls back) with the action it records.
     *
     * @param detail ids, enums, amounts and booleans ONLY. Never free text,
     *               never a raw phone — this method cannot check that, which is
     *               why every call site builds its map from typed values.
     */
    @Transactional
    public SupportActivity record(SupportAgent agent, SupportActivity.Action action,
                                  String subjectKind, String subjectId, Map<String, Object> detail) {
        SupportActivity row = new SupportActivity();
        row.setAgentUuid(agent.uuid());
        row.setAgentLogin(agent.login());
        row.setAction(action.name());
        row.setSubjectKind(subjectKind);
        row.setSubjectId(subjectId);
        row.setDetail(serialise(detail));
        row.setCreatedAt(Instant.now());
        return activities.save(row);
    }

    /**
     * The phone a lookup session stands for — for the agent who made it, while
     * it is live. Someone else's lookupId, an expired one and an id that was
     * never a lookup are all the same 404, with the same body: the endpoint must
     * not tell an agent that a colleague's lookup exists.
     */
    @Transactional(readOnly = true)
    public SupportActivity requireLiveLookup(SupportAgent agent, UUID lookupId) {
        return activities.findLiveLookup(lookupId, SupportActivity.Action.CUSTOMER_LOOKUP.name(),
                        agent.uuid(), Instant.now().minus(props.lookupTtl()))
                .orElseThrow(() -> new LoyaltyException(HttpStatus.NOT_FOUND, "lookup_not_found",
                        "This lookup has expired or does not exist. Look the customer up again."));
    }

    public Instant expiresAt(SupportActivity lookup) {
        return lookup.getCreatedAt().plus(props.lookupTtl());
    }

    /** The supervisor's feed across every agent, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<SupportDtos.ActivityResponse> feed(String agentUuid, SupportActivity.Action action,
                                                           Instant from, Instant to, int page, int size) {
        SupportPaging.requireOrderedRange(from, to);
        Specification<SupportActivity> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (agentUuid != null && !agentUuid.isBlank()) {
                where.add(cb.equal(root.get("agentUuid"), agentUuid.strip()));
            }
            if (action != null) {
                where.add(cb.equal(root.get("action"), action.name()));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                where.add(cb.lessThan(root.get("createdAt"), to));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Pageable pageable = PageRequest.of(SupportPaging.page(page), SupportPaging.size(size),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return PageResponse.from(activities.findAll(spec, pageable).map(this::toResponse));
    }

    public SupportDtos.ActivityResponse toResponse(SupportActivity a) {
        return new SupportDtos.ActivityResponse(a.getId(),
                new SupportDtos.AgentRef(a.getAgentUuid(), a.getAgentLogin()),
                a.getAction(), a.getSubjectKind(),
                maskSubject(a.getSubjectKind(), a.getSubjectId()),
                deserialise(a.getDetail()), a.getCreatedAt());
    }

    /** A PHONE subject is shown masked on every response; any other subject id is an opaque id. */
    public static String maskSubject(String subjectKind, String subjectId) {
        if (subjectId == null) {
            return null;
        }
        return SupportActivity.SUBJECT_PHONE.equals(subjectKind) ? MsisdnMasking.mask(subjectId) : subjectId;
    }

    /** An insertion-ordered detail map that tolerates null values (they are simply left out). */
    public static Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                Object v = value instanceof Number || value instanceof Boolean ? value
                        : value instanceof Enum<?> e ? e.name()
                        : value.toString();
                out.put(String.valueOf(keyValues[i]), v);
            }
        }
        return out;
    }

    private String serialise(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            String out = json.writeValueAsString(detail);
            if (out.length() <= MAX_DETAIL) {
                return out;
            }
            // Only ids and enums go in, so this is a programming error — but an
            // oversight row is worth more slightly thinner than not at all.
            log.warn("Support activity detail of {} chars exceeds {}; keys kept: {}", out.length(), MAX_DETAIL,
                    detail.keySet());
            return "{\"detailTruncated\":true}";
        } catch (Exception e) {
            log.warn("Support activity detail could not be serialised: {}", e.toString());
            return null;
        }
    }

    private Map<String, Object> deserialise(String detail) {
        if (detail == null || detail.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(detail, DETAIL_TYPE);
        } catch (Exception e) {
            log.warn("Support activity detail could not be parsed: {}", e.toString());
            return Map.of();
        }
    }
}
