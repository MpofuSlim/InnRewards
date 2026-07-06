package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.Shop;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.ShopRepository;
import com.innbucks.loyaltyservice.util.HtmlSanitizer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ShopService {

    /**
     * Upper bound on rows accepted in a single bulk upload. Protects the
     * service from a 500MB CSV holding a thread for minutes. A single
     * merchant launching 9 African markets at once is ~hundreds of
     * outlets, so 5000 is comfortably above any realistic onboarding
     * batch but still cheap to reject the pathological case.
     */
    private static final int MAX_BULK_ROWS = 5000;

    private final ShopRepository shops;
    private final MerchantService merchants;
    private final TransactionTemplate txTemplate;

    public ShopService(ShopRepository shops, MerchantService merchants, PlatformTransactionManager txManager) {
        this.shops = shops;
        this.merchants = merchants;
        // Each row of a bulk upload runs in its own (default-propagation)
        // tx via this template — a failure on row N rolls back only row N.
        this.txTemplate = new TransactionTemplate(txManager);
    }

    public Dtos.ShopResponse create(UUID tenantId, Dtos.ShopRequest req) {
        Merchant m = merchants.requireMerchant(tenantId, req.merchantId());

        // Duplicate-name guard: a merchant can't have two shops with the same
        // name (case-insensitive). Trim first so " Avondale" and "Avondale" collide.
        String name = req.name() == null ? "" : req.name().trim();
        if (shops.existsByMerchantIdAndNameIgnoreCase(m.getId(), name)) {
            throw LoyaltyException.conflict("SHOP_NAME_TAKEN",
                    "A shop with that name already exists for this merchant.");
        }

        Shop s = new Shop();
        s.setTenantId(tenantId);
        s.setMerchantId(m.getId());
        // Strip any HTML from the free-text fields before persisting (stored-XSS
        // hardening). Dedup above still runs on the raw trimmed name; stripAll is
        // a no-op on legitimate names, so the guard is unchanged for real input.
        s.setName(HtmlSanitizer.stripAll(name));
        s.setAddress(HtmlSanitizer.stripAll(req.address()));
        shops.save(s);
        return toResponse(s);
    }

    @Transactional(readOnly = true)
    public Page<Dtos.ShopResponse> list(UUID tenantId, UUID merchantFilter, Pageable pageable) {
        Page<Shop> page = merchantFilter == null
                ? shops.findByTenantId(tenantId, pageable)
                : shops.findByTenantIdAndMerchantId(tenantId, merchantFilter, pageable);
        return page.map(ShopService::toResponse);
    }

    @Transactional(readOnly = true)
    public List<Dtos.ShopResponse> listForMerchant(UUID tenantId, UUID merchantId) {
        merchants.requireMerchant(tenantId, merchantId);
        return shops.findByTenantIdAndMerchantId(tenantId, merchantId)
                .stream().map(ShopService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Dtos.ShopResponse get(UUID tenantId, UUID shopId) {
        return toResponse(requireShop(tenantId, shopId));
    }

    /**
     * Loads a shop by id WITHOUT a tenant scope. Used by the public
     * guest-checkout endpoint (TODO(demo)) where an anonymous caller has no
     * tenant membership to scope by — the shop carries its own tenantId and
     * merchantId, which the downstream checkout resolves from the shop. Uses the
     * same {@link #toResponse(Shop)} mapper as {@link #get(UUID, UUID)}.
     */
    @Transactional(readOnly = true)
    public Dtos.ShopResponse getById(UUID shopId) {
        Shop s = shops.findById(shopId).orElseThrow(() -> LoyaltyException.notFound("shop"));
        return toResponse(s);
    }

    public Dtos.ShopResponse update(UUID tenantId, UUID shopId, Dtos.ShopRequest req) {
        Shop s = requireShop(tenantId, shopId);
        s.setName(HtmlSanitizer.stripAll(req.name()));
        if (req.address() != null) s.setAddress(HtmlSanitizer.stripAll(req.address()));
        return toResponse(s);
    }

    public Dtos.ShopResponse setActive(UUID tenantId, UUID shopId, boolean active) {
        Shop s = requireShop(tenantId, shopId);
        s.setStatus(active ? Shop.Status.ACTIVE : Shop.Status.INACTIVE);
        return toResponse(s);
    }

    public Shop requireShop(UUID tenantId, UUID shopId) {
        Shop s = shops.findById(shopId).orElseThrow(() -> LoyaltyException.notFound("shop"));
        if (!s.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "shop belongs to a different tenant");
        }
        return s;
    }

    public static Dtos.ShopResponse toResponse(Shop s) {
        return new Dtos.ShopResponse(s.getId(), s.getTenantId(), s.getMerchantId(),
                s.getName(), s.getAddress(), s.getStatus(), s.getCreatedAt());
    }

    /**
     * Bulk-upload outlets from a CSV. Each row gets its own transaction so
     * a validation failure on row 7 doesn't roll back rows 1–6. The result
     * lists every failed row with the human-readable reason, so the
     * operator can correct the source spreadsheet and re-upload only the
     * affected rows.
     *
     * <p>Expected CSV shape: a header row of {@code name,address} followed
     * by data rows. The header is matched case-insensitively and tolerates
     * extra columns (they're ignored) so a spreadsheet export with bonus
     * columns doesn't blow up. Quoted fields support embedded commas
     * ({@code "123 King George Rd, Avondale, Harare"}) and escaped quotes
     * ({@code ""}).
     *
     * <p>{@code propagation = NOT_SUPPORTED} on the outer method so the
     * inherited class-level {@code @Transactional} doesn't wrap the whole
     * call in a single tx — defeating the per-row independence we want.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Dtos.BulkShopUploadResult bulkUploadFromCsv(UUID tenantId, UUID merchantId, java.io.InputStream csv) {
        // Validate merchant scope once up front — every row of the CSV
        // attaches to the same merchant, so re-checking per row would be
        // wasted DB hits.
        Merchant m = merchants.requireMerchant(tenantId, merchantId);

        List<String[]> rows;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            rows = parseCsv(reader);
        } catch (IOException ex) {
            throw LoyaltyException.badRequest("CSV_READ_FAILED", "could not read uploaded file: " + ex.getMessage());
        }

        if (rows.isEmpty()) {
            throw LoyaltyException.badRequest("CSV_EMPTY", "uploaded file has no rows");
        }

        String[] header = rows.get(0);
        int nameCol = indexOfHeader(header, "name");
        int addressCol = indexOfHeader(header, "address");
        if (nameCol < 0) {
            throw LoyaltyException.badRequest("CSV_MISSING_HEADER",
                    "header row must contain a 'name' column (case-insensitive); got: "
                            + String.join(",", header));
        }

        int dataRowCount = rows.size() - 1;
        if (dataRowCount > MAX_BULK_ROWS) {
            throw LoyaltyException.badRequest("CSV_TOO_LARGE",
                    "upload contains " + dataRowCount + " rows; max is " + MAX_BULK_ROWS);
        }

        int created = 0;
        List<Dtos.BulkShopRowFailure> failures = new ArrayList<>();
        // Names already accepted in THIS file, lower-cased — so two rows with the
        // same name (any casing) don't both insert; the first wins and later ones
        // are marked as failed rather than creating a duplicate.
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            // 1-based row number for human display: header is row 1, first
            // data row is row 2. Matches how spreadsheet apps number rows.
            int rowNumber = i + 1;
            String[] row = rows.get(i);
            String name = nameCol < row.length ? row[nameCol].trim() : "";
            String address = (addressCol >= 0 && addressCol < row.length)
                    ? nullIfBlank(row[addressCol].trim()) : null;

            if (name.isBlank()) {
                failures.add(new Dtos.BulkShopRowFailure(rowNumber, null, "name is required"));
                continue;
            }

            // Duplicate-name guard, mirroring the single-create path: reject a row
            // whose name duplicates another row already accepted in this file OR an
            // existing shop under the merchant (both case-insensitive). No insert —
            // the row is reported as a failure so the operator can fix the source.
            if (!seenNames.add(name.toLowerCase())
                    || shops.existsByMerchantIdAndNameIgnoreCase(m.getId(), name)) {
                failures.add(new Dtos.BulkShopRowFailure(rowNumber, name, "duplicate shop name"));
                continue;
            }

            try {
                final String finalName = name;
                final String finalAddress = address;
                txTemplate.executeWithoutResult(status -> {
                    Shop s = new Shop();
                    s.setTenantId(tenantId);
                    s.setMerchantId(m.getId());
                    s.setName(HtmlSanitizer.stripAll(finalName));
                    s.setAddress(HtmlSanitizer.stripAll(finalAddress));
                    shops.save(s);
                });
                created++;
            } catch (RuntimeException ex) {
                // Capture the row-level failure without aborting the batch.
                // The transaction template already rolled back this row's
                // insert; future rows continue cleanly.
                String reason = ex.getMessage();
                failures.add(new Dtos.BulkShopRowFailure(rowNumber, name,
                        reason == null ? ex.getClass().getSimpleName() : reason));
            }
        }

        return new Dtos.BulkShopUploadResult(dataRowCount, created, failures.size(), failures);
    }

    private static int indexOfHeader(String[] header, String wanted) {
        for (int i = 0; i < header.length; i++) {
            if (wanted.equalsIgnoreCase(header[i].trim())) return i;
        }
        return -1;
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * Minimal RFC 4180-ish CSV reader: quote-aware (so commas in addresses
     * work), supports escaped quotes via {@code ""}, skips fully blank
     * lines. Deliberately doesn't pull in a CSV library — the only shape
     * we accept is shop name + address, and a stand-alone library would
     * eclipse the parser's actual surface area.
     */
    static List<String[]> parseCsv(BufferedReader reader) throws IOException {
        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        int c;
        while ((c = reader.read()) != -1) {
            char ch = (char) c;
            if (inQuotes) {
                if (ch == '"') {
                    // Peek for an escaped quote (`""` -> literal `"`).
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        inQuotes = false;
                        if (next != -1) reader.reset();
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                currentRow.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                // Swallow \r\n as a single line break, and skip blank lines.
                if (ch == '\r') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next != '\n' && next != -1) reader.reset();
                }
                if (!field.isEmpty() || !currentRow.isEmpty()) {
                    currentRow.add(field.toString());
                    rows.add(currentRow.toArray(new String[0]));
                    currentRow = new ArrayList<>();
                    field.setLength(0);
                }
            } else {
                field.append(ch);
            }
        }
        // Trailing line without a final \n.
        if (!field.isEmpty() || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            rows.add(currentRow.toArray(new String[0]));
        }
        return rows;
    }
}
