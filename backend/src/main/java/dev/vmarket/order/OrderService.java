package dev.vmarket.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private static final int FREE_SHIPPING_THRESHOLD = 12_000;
    private static final int SHIPPING_FEE = 900;
    private static final int TAX_PERCENT = 8;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final CheckoutTrackingTokens trackingTokens;

    public OrderService(JdbcClient jdbc, ObjectMapper objectMapper, CheckoutTrackingTokens trackingTokens) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.trackingTokens = trackingTokens;
    }

    @Transactional(timeout = 10)
    public OrderResult create(String checkoutSession, String idempotencyKey, CreateOrderRequest request) {
        configureWaitLimits();
        var identity = identity(checkoutSession, idempotencyKey);
        String suppliedKey = idempotencyKey;
        String scopeHash = identity.scopeHash();
        idempotencyKey = identity.key();

        // Serialize the key before looking up its response; stock locks protect a different invariant.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "vmarket:checkout:" + idempotencyKey)
                .query((rs, row) -> true)
                .single();
        requireNotClosed(idempotencyKey);
        if (jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM customer_order WHERE idempotency_key=:key AND checkout_scope_hash IS NULL)")
                .param("key", suppliedKey)
                .query(Boolean.class)
                .single())
            throw new IdempotencyConflictException(
                    "This key belongs to a historical checkout without verified guest ownership. Use the original tracking link or request operator review; do not submit it as a new order.");
        var quantities = normalizedQuantities(request.lines());
        String requestHash = requestHash(request.customer(), quantities);
        var existing = findByIdempotencyKey(idempotencyKey, scopeHash, requestHash);
        if (existing != null) {
            return existing;
        }

        var products = lockProducts(quantities.keySet());
        if (products.size() != quantities.size()) {
            throw new InvalidOrderException("One or more products do not exist.");
        }

        int subtotal = 0;
        for (var product : products) {
            int quantity = quantities.get(product.sku());
            if (quantity > product.availableQuantity()) {
                throw new InventoryConflictException(product.sku() + " has insufficient inventory.");
            }
            subtotal = Math.addExact(subtotal, Math.multiplyExact(product.priceMinor(), quantity));
        }

        int shipping = subtotal >= FREE_SHIPPING_THRESHOLD ? 0 : SHIPPING_FEE;
        int tax = Math.toIntExact(Math.round(subtotal * (TAX_PERCENT / 100.0)));
        int grandTotal = Math.addExact(Math.addExact(subtotal, shipping), tax);
        UUID orderId = UUID.randomUUID();
        String orderNumber =
                "VM-" + orderId.toString().replace("-", "").substring(0, 12).toUpperCase();
        String trackingToken = trackingTokens.create();
        String trackingTokenHash = TokenHash.sha256(trackingToken);
        Instant createdAt = jdbc.sql("SELECT clock_timestamp()")
                .query((rs, row) -> rs.getTimestamp(1).toInstant())
                .single();

        var customer = request.customer();
        jdbc.sql("""
                        INSERT INTO customer_order (
                            id, order_number, tracking_token_hash, idempotency_key,
                            customer_name, customer_email, postal_code, prefecture, city, address_line,
                            payment_method, subtotal_minor, shipping_minor, tax_minor, grand_total_minor,
                            privacy_accepted_at, created_at, updated_at, request_hash, checkout_scope_hash, tracking_replay_ciphertext
                        ) VALUES (
                            :id, :orderNumber, :trackingHash, :idempotencyKey,
                            :name, :email, :postalCode, :prefecture, :city, :addressLine,
                            :paymentMethod, :subtotal, :shipping, :tax, :grandTotal,
                            :createdAt, :createdAt, :createdAt, :requestHash, :scopeHash, :trackingReplay
                        )
                        """)
                .param("id", orderId)
                .param("requestHash", requestHash)
                .param("scopeHash", scopeHash)
                .param("trackingReplay", trackingTokens.protect(orderId, trackingToken))
                .param("orderNumber", orderNumber)
                .param("trackingHash", trackingTokenHash)
                .param("idempotencyKey", idempotencyKey)
                .param("name", customer.name().trim())
                .param("email", customer.email().trim().toLowerCase())
                .param("postalCode", customer.postalCode().trim())
                .param("prefecture", customer.prefecture().trim())
                .param("city", customer.city().trim())
                .param("addressLine", customer.addressLine().trim())
                .param("paymentMethod", customer.paymentMethod())
                .param("subtotal", subtotal)
                .param("shipping", shipping)
                .param("tax", tax)
                .param("grandTotal", grandTotal)
                .param("createdAt", Timestamp.from(createdAt))
                .update();

        for (var product : products) {
            int quantity = quantities.get(product.sku());
            jdbc.sql("""
                            INSERT INTO order_item (
                                order_id, product_id, sku, product_name, unit_price_minor, quantity
                            ) VALUES (:orderId, :productId, :sku, :name, :price, :quantity)
                            """)
                    .param("orderId", orderId)
                    .param("productId", product.id())
                    .param("sku", product.sku())
                    .param("name", product.name())
                    .param("price", product.priceMinor())
                    .param("quantity", quantity)
                    .update();
            jdbc.sql("""
                            UPDATE inventory_stock
                            SET on_hand = on_hand - :quantity, version = version + 1, updated_at = NOW()
                            WHERE product_id = :productId
                            """)
                    .param("quantity", quantity)
                    .param("productId", product.id())
                    .update();
            jdbc.sql("""
                            INSERT INTO inventory_movement (
                                id, product_id, movement_type, quantity_delta, reason, reference_id
                            ) VALUES (:id, :productId, 'ORDER_PLACED', :delta, 'Checkout confirmed', :reference)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("productId", product.id())
                    .param("delta", -quantity)
                    .param("reference", orderNumber)
                    .update();
        }

        audit(orderId, orderNumber, products.size());
        return new OrderResult(
                orderNumber,
                trackingToken,
                "PENDING",
                "RECEIVED",
                subtotal,
                shipping,
                tax,
                grandTotal,
                "JPY",
                createdAt);
    }

    private Map<String, Integer> normalizedQuantities(List<OrderLineRequest> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new InvalidOrderException("At least one order line is required.");
        }
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (var line : lines) {
            if (line == null || line.sku() == null || line.sku().isBlank()) {
                throw new InvalidOrderException("Every order line requires a SKU.");
            }
            if (line.quantity() < 1 || line.quantity() > 99) {
                throw new InvalidOrderException("Order quantities must be between 1 and 99.");
            }
            quantities.merge(line.sku().trim().toUpperCase(Locale.ROOT), line.quantity(), Math::addExact);
        }
        if (quantities.values().stream().anyMatch(quantity -> quantity > 99)) {
            throw new InvalidOrderException("Combined order quantities must not exceed 99.");
        }
        return quantities;
    }

    private List<LockedProduct> lockProducts(Iterable<String> skus) {
        List<String> orderedSkus = new ArrayList<>();
        skus.forEach(orderedSkus::add);
        orderedSkus.sort(String::compareTo);
        return jdbc.sql("""
                        SELECT p.id, p.sku, p.name, p.price_minor, s.on_hand - s.reserved AS available_quantity
                        FROM product p JOIN inventory_stock s ON s.product_id = p.id
                        WHERE p.active = TRUE AND p.sku IN (:skus)
                        ORDER BY p.sku
                        FOR UPDATE OF p, s
                        """)
                .param("skus", orderedSkus)
                .query(LockedProduct.class)
                .list();
    }

    private String requestHash(CustomerRequest customer, Map<String, Integer> quantities) {
        var normalized = new CustomerRequest(
                customer.name().trim(),
                customer.email().trim().toLowerCase(Locale.ROOT),
                customer.postalCode().trim(),
                customer.prefecture().trim(),
                customer.city().trim(),
                customer.addressLine().trim(),
                customer.paymentMethod(),
                customer.privacyAccepted());
        try {
            return TokenHash.sha256(
                    objectMapper.writeValueAsString(new CheckoutFingerprint(normalized, new TreeMap<>(quantities))));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not fingerprint the checkout request", exception);
        }
    }

    private record CheckoutFingerprint(CustomerRequest customer, Map<String, Integer> quantities) {}

    private record CheckoutIdentity(String scopeHash, String key) {}

    private CheckoutIdentity identity(String guest, String key) {
        if (key == null || key.isBlank() || key.length() > 120)
            throw new InvalidOrderException("A valid Idempotency-Key header is required.");
        if (guest == null || !guest.matches("[0-9a-f]{64}"))
            throw new InvalidOrderException("A valid X-Checkout-Session guest capability is required.");
        String scope = TokenHash.sha256(guest);
        return new CheckoutIdentity(scope, TokenHash.sha256("guest-checkout:v1:" + scope + ":" + key));
    }

    @Transactional(readOnly = true, timeout = 10)
    public OrderResult recover(String guest, String key) {
        configureWaitLimits();
        var identity = identity(guest, key);
        requireNotClosed(identity.key());
        var result = findByIdempotencyKey(identity.key(), identity.scopeHash(), null);
        if (result == null) throw new CheckoutNotObservedException();
        return result;
    }

    @Transactional(timeout = 10)
    public void closeUnused(String guest, String key) {
        var identity = identity(guest, key);
        configureWaitLimits();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "vmarket:checkout:" + identity.key())
                .query((rs, row) -> true)
                .single();
        boolean exists = jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM customer_order WHERE idempotency_key=:physical OR (idempotency_key=:raw AND checkout_scope_hash IS NULL))")
                .param("physical", identity.key())
                .param("raw", key)
                .query(Boolean.class)
                .single();
        if (exists)
            throw new IdempotencyConflictException(
                    "An existing or historical order cannot be closed by this operation. Check the checkout result or request operator review.");
        // All close writers take the per-key lock first, then this shared capacity lock.
        // Never evict a tombstone: doing so would let a delayed original request create a sale.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('vmarket:closed-checkout-budget',0))")
                .query((rs, row) -> true)
                .single();
        if (isClosed(identity.key())) return;
        if (jdbc.sql("SELECT COUNT(*) FROM closed_checkout_intent")
                        .query(Long.class)
                        .single()
                >= 10_000) throw new CheckoutCloseCapacityException();
        jdbc.sql("INSERT INTO closed_checkout_intent(key_hash) VALUES(:key) ON CONFLICT DO NOTHING")
                .param("key", identity.key())
                .update();
    }

    private void requireNotClosed(String key) {
        if (isClosed(key)) throw new CheckoutCancelledException();
    }

    private void configureWaitLimits() {
        jdbc.sql("SET LOCAL lock_timeout='3s'").update();
        jdbc.sql("SET LOCAL statement_timeout='8s'").update();
    }

    private boolean isClosed(String key) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM closed_checkout_intent WHERE key_hash=:key)")
                .param("key", key)
                .query(Boolean.class)
                .single();
    }

    private OrderResult findByIdempotencyKey(String idempotencyKey, String scopeHash, String requestHash) {
        return jdbc.sql("""
                        SELECT id, order_number, payment_status, fulfillment_status, subtotal_minor,
                               shipping_minor, tax_minor, grand_total_minor, currency, created_at, request_hash, checkout_scope_hash,
                               tracking_token_hash, tracking_replay_ciphertext,
                               created_at > clock_timestamp()-INTERVAL '24 hours' AS replay_current
                        FROM customer_order WHERE idempotency_key = :key
                        """)
                .param("key", idempotencyKey)
                .query((rs, rowNum) -> {
                    if (!scopeHash.equals(rs.getString("checkout_scope_hash"))
                            || (requestHash != null && !requestHash.equals(rs.getString("request_hash")))) {
                        throw new IdempotencyConflictException(
                                "The key belongs to a different or legacy checkout request. Read the existing order before submitting a new operation.");
                    }
                    if (!rs.getBoolean("replay_current"))
                        throw new IdempotencyConflictException(
                                "The 24-hour checkout replay window has expired. This key remains reserved; do not submit another order to resolve an unknown outcome. Use the original tracking link or request operator review.");
                    return new OrderResult(
                            rs.getString("order_number"),
                            trackingTokens.reveal(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("tracking_replay_ciphertext"),
                                    rs.getString("tracking_token_hash")),
                            rs.getString("payment_status"),
                            rs.getString("fulfillment_status"),
                            rs.getInt("subtotal_minor"),
                            rs.getInt("shipping_minor"),
                            rs.getInt("tax_minor"),
                            rs.getInt("grand_total_minor"),
                            rs.getString("currency"),
                            rs.getTimestamp("created_at").toInstant());
                })
                .optional()
                .orElse(null);
    }

    private void audit(UUID orderId, String orderNumber, int lineCount) {
        try {
            String details =
                    objectMapper.writeValueAsString(Map.of("orderNumber", orderNumber, "lineCount", lineCount));
            jdbc.sql("""
                            INSERT INTO audit_event (id, actor, action, aggregate_type, aggregate_id, details)
                            VALUES (:id, 'guest-checkout', 'ORDER_CREATED', 'ORDER', :aggregateId, CAST(:details AS JSONB))
                            """)
                    .param("id", UUID.randomUUID())
                    .param("aggregateId", orderId.toString())
                    .param("details", details)
                    .update();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize audit details", exception);
        }
    }

    private record LockedProduct(UUID id, String sku, String name, int priceMinor, int availableQuantity) {}
}
