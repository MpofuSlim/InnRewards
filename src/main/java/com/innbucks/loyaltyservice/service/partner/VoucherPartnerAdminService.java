package com.innbucks.loyaltyservice.service.partner;

import com.innbucks.loyaltyservice.dto.PartnerAdminDtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.VoucherPartner;
import com.innbucks.loyaltyservice.entity.VoucherPartnerKey;
import com.innbucks.loyaltyservice.entity.VoucherPartnerMerchant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherPartnerKeyRepository;
import com.innbucks.loyaltyservice.repository.VoucherPartnerMerchantRepository;
import com.innbucks.loyaltyservice.repository.VoucherPartnerRepository;
import com.innbucks.loyaltyservice.security.IpLiterals;
import com.innbucks.loyaltyservice.security.PartnerSigningKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One-time setup of the partner voucher API (V62), by a SUPER_ADMIN: register a
 * partner (the calling system), its public keys, and the merchants it may act
 * for. Linking a merchant puts it in escrow mode, so its vouchers then exist only
 * through the partner.
 */
@Service
@Transactional
@Slf4j
public class VoucherPartnerAdminService {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9_-]{2,32}");
    private static final Pattern KID = Pattern.compile("[A-Za-z0-9._-]{3,64}");

    private final VoucherPartnerRepository partners;
    private final VoucherPartnerKeyRepository keys;
    private final VoucherPartnerMerchantRepository links;
    private final MerchantRepository merchants;

    public VoucherPartnerAdminService(VoucherPartnerRepository partners, VoucherPartnerKeyRepository keys,
                                      VoucherPartnerMerchantRepository links, MerchantRepository merchants) {
        this.partners = partners;
        this.keys = keys;
        this.links = links;
        this.merchants = merchants;
    }

    @Transactional(readOnly = true)
    public List<PartnerAdminDtos.PartnerView> list() {
        return partners.findAll().stream().map(this::view).toList();
    }

    public PartnerAdminDtos.PartnerView create(PartnerAdminDtos.CreatePartnerRequest req) {
        String issuer = req.issuer().strip();
        if (partners.findByIssuer(issuer).isPresent()) {
            throw LoyaltyException.conflict("PARTNER_EXISTS", "A partner with this issuer already exists.");
        }
        VoucherPartner p = new VoucherPartner();
        p.setName(req.name().strip());
        p.setIssuer(issuer);
        p.setAllowedIps(allowList(req.allowedIps()));
        partners.save(p);
        log.info("Voucher partner registered partnerId={} issuer={}", p.getId(), issuer);
        return view(p);
    }

    public PartnerAdminDtos.PartnerView update(UUID partnerId, PartnerAdminDtos.UpdatePartnerRequest req) {
        VoucherPartner p = requirePartner(partnerId);
        if (req.allowedIps() != null) {
            p.setAllowedIps(allowList(req.allowedIps()));
        }
        if (req.status() != null) {
            p.setStatus(status(req.status()));
        }
        p.setUpdatedAt(Instant.now());
        log.info("Voucher partner updated partnerId={} status={}", p.getId(), p.getStatus());
        return view(p);
    }

    public PartnerAdminDtos.PartnerView addKey(UUID partnerId, PartnerAdminDtos.AddKeyRequest req) {
        VoucherPartner p = requirePartner(partnerId);
        String kid = req.kid().strip();
        if (!KID.matcher(kid).matches()) {
            throw LoyaltyException.badRequest("BAD_KID", "A key id is 3 to 64 letters, digits, dots, dashes or underscores.");
        }
        if (keys.findByKid(kid).isPresent()) {
            throw LoyaltyException.conflict("KID_EXISTS", "This key id is already registered.");
        }
        VoucherPartnerKey.Alg alg;
        try {
            alg = VoucherPartnerKey.Alg.valueOf(req.alg().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw LoyaltyException.badRequest("BAD_ALG", "The algorithm must be ES256 or RS256.");
        }
        try {
            PartnerSigningKeys.parse(req.publicKeyPem(), alg);
        } catch (IllegalArgumentException e) {
            throw LoyaltyException.badRequest("BAD_PUBLIC_KEY", e.getMessage());
        }
        VoucherPartnerKey key = new VoucherPartnerKey();
        key.setPartnerId(p.getId());
        key.setKid(kid);
        key.setAlg(alg);
        key.setPublicKeyPem(req.publicKeyPem().strip());
        keys.save(key);
        log.info("Voucher partner key added partnerId={} kid={} alg={}", p.getId(), kid, alg);
        return view(p);
    }

    public PartnerAdminDtos.PartnerView revokeKey(UUID partnerId, String kid) {
        VoucherPartner p = requirePartner(partnerId);
        VoucherPartnerKey key = keys.findByKid(kid)
                .filter(k -> k.getPartnerId().equals(p.getId()))
                .orElseThrow(() -> LoyaltyException.notFound("key"));
        if (key.getStatus() != VoucherPartnerKey.Status.REVOKED) {
            key.setStatus(VoucherPartnerKey.Status.REVOKED);
            key.setRevokedAt(Instant.now());
            log.info("Voucher partner key revoked partnerId={} kid={}", p.getId(), kid);
        }
        return view(p);
    }

    public PartnerAdminDtos.PartnerView linkMerchant(UUID partnerId, PartnerAdminDtos.LinkMerchantRequest req) {
        VoucherPartner p = requirePartner(partnerId);
        String code = req.merchantCode().strip().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw LoyaltyException.badRequest("BAD_MERCHANT_CODE",
                    "A merchant code is 2 to 32 letters, digits, dashes or underscores.");
        }
        Merchant merchant = merchants.findById(req.merchantId())
                .orElseThrow(() -> LoyaltyException.notFound("merchant"));
        if (links.findByPartnerIdAndMerchantCode(p.getId(), code).isPresent()
                || links.findByPartnerIdAndMerchantId(p.getId(), merchant.getId()).isPresent()) {
            throw LoyaltyException.conflict("MERCHANT_ALREADY_LINKED",
                    "This merchant, or this merchant code, is already linked to the partner.");
        }
        VoucherPartnerMerchant link = new VoucherPartnerMerchant();
        link.setPartnerId(p.getId());
        link.setMerchantId(merchant.getId());
        link.setTenantId(merchant.getTenantId());
        link.setMerchantCode(code);
        applySettings(link, req.requireTerminal(), req.bindRedeemToHolder(), req.fundingMethods(), req.maxValue(),
                req.perPhoneDailyCount(), req.perPhoneDailyValue());
        links.save(link);
        // From now on the merchant's vouchers exist only through the partner.
        merchant.setEscrowMode(true);
        log.info("Merchant linked to voucher partner partnerId={} merchantId={} code={} (escrow mode on)",
                p.getId(), merchant.getId(), code);
        return view(p);
    }

    public PartnerAdminDtos.PartnerView updateMerchant(UUID partnerId, String merchantCode,
                                                       PartnerAdminDtos.UpdateMerchantRequest req) {
        VoucherPartner p = requirePartner(partnerId);
        VoucherPartnerMerchant link = links.findByPartnerIdAndMerchantCode(p.getId(),
                        merchantCode.strip().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> LoyaltyException.notFound("merchant link"));
        applySettings(link, req.requireTerminal(), req.bindRedeemToHolder(), req.fundingMethods(), req.maxValue(),
                req.perPhoneDailyCount(), req.perPhoneDailyValue());
        if (req.status() != null) {
            link.setStatus(status(req.status()));
        }
        link.setUpdatedAt(Instant.now());
        return view(p);
    }

    private static void applySettings(VoucherPartnerMerchant link, Boolean requireTerminal, Boolean bindRedeemToHolder,
                                      List<String> fundingMethods, BigDecimal maxValue, Integer perPhoneDailyCount,
                                      BigDecimal perPhoneDailyValue) {
        if (requireTerminal != null) {
            link.setRequireTerminal(requireTerminal);
        }
        if (bindRedeemToHolder != null) {
            link.setBindRedeemToHolder(bindRedeemToHolder);
        }
        if (fundingMethods != null) {
            List<String> methods = fundingMethods.stream().map(m -> m.strip().toUpperCase(Locale.ROOT))
                    .filter(m -> !m.isEmpty()).distinct().toList();
            for (String m : methods) {
                if (!PartnerVoucherService.METHODS.contains(m)) {
                    throw LoyaltyException.badRequest("BAD_FUNDING_METHOD", "Unknown funding method " + m + ".");
                }
            }
            link.setFundingMethods(methods.isEmpty() ? null : String.join(",", methods));
        }
        if (maxValue != null) {
            link.setMaxValue(positive(maxValue, "maxValue"));
        }
        if (perPhoneDailyCount != null) {
            if (perPhoneDailyCount < 1) {
                throw LoyaltyException.badRequest("BAD_LIMIT", "perPhoneDailyCount must be at least 1.");
            }
            link.setPerPhoneDailyCount(perPhoneDailyCount);
        }
        if (perPhoneDailyValue != null) {
            link.setPerPhoneDailyValue(positive(perPhoneDailyValue, "perPhoneDailyValue"));
        }
    }

    private static BigDecimal positive(BigDecimal v, String field) {
        if (v.signum() <= 0) {
            throw LoyaltyException.badRequest("BAD_LIMIT", field + " must be positive.");
        }
        return v;
    }

    private static VoucherPartner.Status status(String s) {
        try {
            return VoucherPartner.Status.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw LoyaltyException.badRequest("BAD_STATUS", "The status must be ACTIVE or SUSPENDED.");
        }
    }

    private static String allowList(List<String> ips) {
        String joined = ips == null ? "" : ips.stream().map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.joining(","));
        try {
            IpLiterals.requireValidAllowList(joined);
        } catch (IllegalArgumentException e) {
            throw LoyaltyException.badRequest("BAD_IP", e.getMessage());
        }
        if (joined.length() > 1000) {
            throw LoyaltyException.badRequest("BAD_IP", "The allow-list is too long.");
        }
        return joined;
    }

    private VoucherPartner requirePartner(UUID partnerId) {
        return partners.findById(partnerId).orElseThrow(() -> LoyaltyException.notFound("partner"));
    }

    private PartnerAdminDtos.PartnerView view(VoucherPartner p) {
        List<PartnerAdminDtos.KeyView> keyViews = keys.findByPartnerIdOrderByCreatedAtAsc(p.getId()).stream()
                .map(k -> new PartnerAdminDtos.KeyView(k.getKid(), k.getAlg().name(), k.getStatus().name(),
                        k.getCreatedAt(), k.getRevokedAt()))
                .toList();
        List<PartnerAdminDtos.MerchantLinkView> merchantViews = links.findByPartnerIdOrderByMerchantCodeAsc(p.getId())
                .stream()
                .map(l -> new PartnerAdminDtos.MerchantLinkView(l.getMerchantCode(), l.getMerchantId(),
                        l.getTenantId(), l.getStatus().name(), l.isRequireTerminal(), l.isBindRedeemToHolder(),
                        l.getFundingMethods() == null ? null : Arrays.asList(l.getFundingMethods().split(",")),
                        l.getMaxValue(), l.getPerPhoneDailyCount(), l.getPerPhoneDailyValue()))
                .toList();
        List<String> ips = p.getAllowedIps() == null || p.getAllowedIps().isBlank() ? List.of()
                : Arrays.asList(p.getAllowedIps().split(","));
        return new PartnerAdminDtos.PartnerView(p.getId(), p.getName(), p.getIssuer(), ips, p.getStatus().name(),
                keyViews, merchantViews);
    }
}
