package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.security.ReportsPermissions;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-only platform access ({@link ReportsPermissions#READ}) stays READ-ONLY.
 *
 * <p>The first test is the guard the permission's name depends on: it walks
 * every handler of every {@code @RestController} in the build that is not a
 * GET, reads its {@code @PreAuthorize} (method and class level), and fails if
 * the permission appears. A write that admits it would hand a developer
 * account the power to change a merchant, a rule or a balance. A
 * {@code @RequestMapping} with no method answers every verb, so it counts as a
 * write.
 *
 * <p>The second pins exactly WHICH GETs admit it, so granting one more is a
 * decision written into this list (and the PR), not a side effect of copying
 * an annotation.
 */
class ReadOnlyPlatformAccessTest {

    /** Every GET that admits the permission. Each one was decided; see the PR. */
    private static final Set<String> GRANTED = Set.of(
            "ReportingController.operator",
            "ReportingController.tenant",
            "ReportingController.merchantsFull",
            "ReportingController.merchant",
            "ReportingController.transactionMix",
            "ReportingController.pointsForMerchant",
            "ReportingController.pointsForShop",
            "ReportingController.pointsByType",
            "ReportingController.pointsTimeSeries",
            "ReportingController.fraud",
            "ReportingController.exportCsv",
            "ReportingController.vouchersOperator",
            "ReportingController.vouchersTenant",
            "ReportingController.vouchersMerchant",
            "ReportingController.vouchersShop",
            "ReportingController.voucherDetail",
            "ReportingController.vouchersExport",
            "ReportingController.vouchersExportOperator",
            "ExchangeRateController.history",
            "RedemptionRateController.history",
            "InvoiceController.listForMerchant",
            "MerchantController.feeAudit",
            "MerchantController.list",
            "MiniAppController.manifest",
            "RuleController.list",
            "RuleController.listCampaigns",
            "ShopController.list",
            "ShopController.get",
            "ShopController.listForMerchant",
            "TenantController.list",
            "TenantController.mine",
            "TenantController.members",
            "TransactionController.myShop",
            "VoucherController.findByStatus");

    /** The granted GETs whose response carries voucher codes: each withholds them. */
    private static final Set<String> CODES_WITHHELD = Set.of(
            "ReportingController.fraud",
            "ReportingController.vouchersOperator",
            "ReportingController.vouchersTenant",
            "ReportingController.vouchersMerchant",
            "ReportingController.vouchersShop",
            "ReportingController.voucherDetail",
            "ReportingController.vouchersExport",
            "ReportingController.vouchersExportOperator",
            "VoucherController.findByStatus");

    /**
     * GETs SUPER_ADMIN reads that deliberately do NOT admit it: one customer's
     * activity (support work, which has its own logged surface), a customer's
     * vouchers by phone (codes), and the till's order poll (the issued code).
     */
    private static final Set<String> NOT_GRANTED = Set.of(
            "ReportingController.user",
            "ReportingController.userByPhone",
            "ReportingController.pointsForUser",
            "ReportingController.pointsForUserByPhone",
            "TransactionController.recent",
            "VoucherController.activeForPhone",
            "VoucherPurchaseController.get");

    @Test
    void noWriteHandlerAdmitsReadOnlyPlatformAccess() {
        List<String> offenders = new ArrayList<>();
        int writes = 0;
        for (Handler h : handlers()) {
            if (h.isGet()) {
                continue;
            }
            writes++;
            if (h.preAuthorize().contains(ReportsPermissions.READ)) {
                offenders.add(h.name() + " " + h.verbs() + " :: " + h.preAuthorize());
            }
        }
        // Vacuity guard: a walk that found no writes proves nothing.
        assertThat(writes).as("non-GET handlers walked").isGreaterThan(40);
        assertThat(offenders)
                .as("non-GET handlers whose @PreAuthorize names %s — read-only access must never "
                        + "reach a write", ReportsPermissions.READ)
                .isEmpty();
    }

    @Test
    void exactlyTheDecidedGetsAdmitIt() {
        Set<String> admitting = new TreeSet<>();
        for (Handler h : handlers()) {
            if (h.isGet() && h.preAuthorize().contains(ReportsPermissions.READ)) {
                admitting.add(h.name());
            }
        }
        assertThat(admitting).containsExactlyInAnyOrderElementsOf(GRANTED);
    }

    @Test
    void customerLevelAndCodeBearingReadsDoNotAdmitIt() {
        Set<String> seen = new TreeSet<>();
        for (Handler h : handlers()) {
            if (NOT_GRANTED.contains(h.name())) {
                seen.add(h.name());
                assertThat(h.preAuthorize()).as(h.name()).doesNotContain(ReportsPermissions.READ);
            }
        }
        // Every name on the list still exists, so a rename cannot empty the check.
        assertThat(seen).containsExactlyInAnyOrderElementsOf(NOT_GRANTED);
    }

    @Test
    void everyGrantedGetSaysSoInSwagger() {
        for (Handler h : handlers()) {
            if (!GRANTED.contains(h.name())) {
                continue;
            }
            Operation op = AnnotatedElementUtils.findMergedAnnotation(h.method(), Operation.class);
            assertThat(op).as("%s has an @Operation", h.name()).isNotNull();
            String expected = CODES_WITHHELD.contains(h.name())
                    ? ReportsPermissions.DOC_CODES_WITHHELD
                    : ReportsPermissions.DOC;
            assertThat(op.description()).as("%s description", h.name()).contains(expected);
        }
    }

    // ── the walk ─────────────────────────────────────────────────────────────

    private record Handler(Class<?> controller, Method method, Set<RequestMethod> verbs, String preAuthorize) {
        String name() {
            return controller.getSimpleName() + "." + method.getName();
        }

        /** GET (HEAD is served by GET handlers). An empty set answers every verb. */
        boolean isGet() {
            return verbs.equals(Set.of(RequestMethod.GET));
        }
    }

    private static List<Handler> handlers() {
        List<Handler> out = new ArrayList<>();
        for (Class<?> c : controllers()) {
            PreAuthorize classLevel = AnnotatedElementUtils.findMergedAnnotation(c, PreAuthorize.class);
            for (Method m : c.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                PreAuthorize methodLevel = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
                String rules = (classLevel == null ? "" : classLevel.value())
                        + " | " + (methodLevel == null ? "" : methodLevel.value());
                out.add(new Handler(c, m, Set.of(mapping.method()), rules));
            }
        }
        assertThat(out).as("handlers found").hasSizeGreaterThan(100);
        return out;
    }

    /** Every {@code @RestController} in the compiled main classes (same walk as
     *  {@link SwaggerResponseCodeUniquenessTest}). */
    private static List<Class<?>> controllers() {
        Path root;
        try {
            root = Path.of(ReadOnlyPlatformAccessTest.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not locate the compiled test classes", e);
        }
        Path classes = root.resolveSibling("classes");
        assertThat(Files.isDirectory(classes)).as("compiled main classes at %s", classes).isTrue();

        List<Class<?>> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classes)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> classes.relativize(p).toString()
                            .replace(java.io.File.separatorChar, '.')
                            .replaceAll("\\.class$", ""))
                    .sorted()
                    .forEach(name -> {
                        try {
                            Class<?> c = Class.forName(name, false,
                                    ReadOnlyPlatformAccessTest.class.getClassLoader());
                            if (c.isAnnotationPresent(RestController.class)) found.add(c);
                        } catch (Throwable ignored) {
                            // Not loadable without Spring: not a controller we can inspect.
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(found).as("controllers found").hasSizeGreaterThan(15);
        return found;
    }
}
