package dev.vmarket.ops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vmarket.order.OrderNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpsOrderService {
    private static final Map<String, String> NEXT_STATUS = Map.of(
            "RECEIVED", "PROCESSING",
            "PROCESSING", "SHIPPED",
            "SHIPPED", "DELIVERED");

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public OpsOrderService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<OpsOrder> recent() {
        return jdbc.sql("""
                        SELECT order_number, payment_status, fulfillment_status,
                               grand_total_minor, currency, created_at
                        FROM customer_order ORDER BY created_at DESC LIMIT 50
                        """)
                .query((rs, rowNumber) -> loadOrder(
                        rs.getString("order_number"),
                        rs.getString("payment_status"),
                        rs.getString("fulfillment_status"),
                        rs.getInt("grand_total_minor"),
                        rs.getString("currency"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Transactional
    public OpsOrder transition(String orderNumber, String requestedStatus) {
        var current = jdbc.sql("""
                        SELECT payment_status, fulfillment_status, grand_total_minor, currency, created_at
                        FROM customer_order WHERE order_number = :orderNumber FOR UPDATE
                        """)
                .param("orderNumber", orderNumber)
                .query((rs, rowNumber) -> new Header(
                        rs.getString("payment_status"),
                        rs.getString("fulfillment_status"),
                        rs.getInt("grand_total_minor"),
                        rs.getString("currency"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional()
                .orElseThrow(OrderNotFoundException::new);
        var normalized = requestedStatus == null ? "" : requestedStatus.trim().toUpperCase();
        if (!normalized.equals(NEXT_STATUS.get(current.fulfillmentStatus()))) {
            throw new InvalidFulfillmentTransitionException(
                    "Fulfillment must advance one step from " + current.fulfillmentStatus() + ".");
        }

        jdbc.sql("""
                        UPDATE customer_order SET fulfillment_status = :status, updated_at = NOW()
                        WHERE order_number = :orderNumber
                        """)
                .param("status", normalized)
                .param("orderNumber", orderNumber)
                .update();
        audit(orderNumber, current.fulfillmentStatus(), normalized);
        return loadOrder(
                orderNumber,
                current.paymentStatus(),
                normalized,
                current.grandTotalMinor(),
                current.currency(),
                current.createdAt());
    }

    private OpsOrder loadOrder(
            String orderNumber,
            String paymentStatus,
            String fulfillmentStatus,
            int grandTotalMinor,
            String currency,
            java.time.Instant createdAt) {
        var lines = jdbc.sql("""
                        SELECT sku, product_name, quantity FROM order_item oi
                        JOIN customer_order o ON o.id = oi.order_id
                        WHERE o.order_number = :orderNumber ORDER BY sku
                        """)
                .param("orderNumber", orderNumber)
                .query(OpsOrderLine.class)
                .list();
        return new OpsOrder(orderNumber, paymentStatus, fulfillmentStatus, grandTotalMinor, currency, createdAt, lines);
    }

    private void audit(String orderNumber, String from, String to) {
        try {
            var details = objectMapper.writeValueAsString(Map.of("from", from, "to", to));
            jdbc.sql("""
                            INSERT INTO audit_event (id, actor, action, aggregate_type, aggregate_id, details)
                            VALUES (:id, 'operations-console', 'FULFILLMENT_STATUS_CHANGED',
                                    'ORDER', :orderNumber, CAST(:details AS JSONB))
                            """)
                    .param("id", UUID.randomUUID())
                    .param("orderNumber", orderNumber)
                    .param("details", details)
                    .update();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize audit details", exception);
        }
    }

    private record Header(
            String paymentStatus,
            String fulfillmentStatus,
            int grandTotalMinor,
            String currency,
            java.time.Instant createdAt) {}
}
