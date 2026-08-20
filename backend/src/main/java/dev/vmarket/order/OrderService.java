package dev.vmarket.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public OrderService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OrderResult create(String idempotencyKey, CreateOrderRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 120) {
            throw new InvalidOrderException("A valid Idempotency-Key header is required.");
        }

        var existing = findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return existing;
        }

        var quantities = normalizedQuantities(request.lines());
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
        UUID orderId = UUID.nameUUIDFromBytes(("order:" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        String orderNumber =
                "VM-" + orderId.toString().replace("-", "").substring(0, 12).toUpperCase();
        String trackingToken = TokenHash.sha256("tracking:" + idempotencyKey);
        String trackingTokenHash = TokenHash.sha256(trackingToken);
        Instant createdAt = Instant.now();

        var customer = request.customer();
        jdbc.sql("""
                        INSERT INTO customer_order (
                            id, order_number, tracking_token_hash, idempotency_key,
                            customer_name, customer_email, postal_code, prefecture, city, address_line,
                            payment_method, subtotal_minor, shipping_minor, tax_minor, grand_total_minor,
                            privacy_accepted_at, created_at, updated_at
                        ) VALUES (
                            :id, :orderNumber, :trackingHash, :idempotencyKey,
                            :name, :email, :postalCode, :prefecture, :city, :addressLine,
                            :paymentMethod, :subtotal, :shipping, :tax, :grandTotal,
                            :createdAt, :createdAt, :createdAt
                        )
                        """)
                .param("id", orderId)
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
            quantities.merge(line.sku().trim().toUpperCase(), line.quantity(), Math::addExact);
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
                        FOR UPDATE OF s
                        """)
                .param("skus", orderedSkus)
                .query(LockedProduct.class)
                .list();
    }

    private OrderResult findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("""
                        SELECT order_number, payment_status, fulfillment_status, subtotal_minor,
                               shipping_minor, tax_minor, grand_total_minor, currency, created_at
                        FROM customer_order WHERE idempotency_key = :key
                        """)
                .param("key", idempotencyKey)
                .query((rs, rowNum) -> new OrderResult(
                        rs.getString("order_number"),
                        TokenHash.sha256("tracking:" + idempotencyKey),
                        rs.getString("payment_status"),
                        rs.getString("fulfillment_status"),
                        rs.getInt("subtotal_minor"),
                        rs.getInt("shipping_minor"),
                        rs.getInt("tax_minor"),
                        rs.getInt("grand_total_minor"),
                        rs.getString("currency"),
                        rs.getTimestamp("created_at").toInstant()))
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
