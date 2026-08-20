package dev.vmarket.order;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderTrackingService {
    private final JdbcClient jdbc;

    public OrderTrackingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public TrackedOrder find(String orderNumber, String trackingToken) {
        if (trackingToken == null || trackingToken.isBlank()) {
            throw new OrderNotFoundException();
        }
        var header = jdbc.sql("""
                        SELECT id, order_number, payment_status, fulfillment_status,
                               grand_total_minor, currency, created_at
                        FROM customer_order
                        WHERE order_number = :number AND tracking_token_hash = :tokenHash
                        """)
                .param("number", orderNumber)
                .param("tokenHash", TokenHash.sha256(trackingToken))
                .query(OrderHeader.class)
                .optional()
                .orElseThrow(OrderNotFoundException::new);
        var items = jdbc.sql("""
                        SELECT sku, product_name AS name, unit_price_minor, quantity
                        FROM order_item WHERE order_id = :orderId ORDER BY sku
                        """)
                .param("orderId", header.id())
                .query(TrackedItem.class)
                .list();
        return new TrackedOrder(
                header.orderNumber(),
                header.paymentStatus(),
                header.fulfillmentStatus(),
                header.grandTotalMinor(),
                header.currency(),
                header.createdAt(),
                items);
    }

    private record OrderHeader(
            UUID id,
            String orderNumber,
            String paymentStatus,
            String fulfillmentStatus,
            int grandTotalMinor,
            String currency,
            Instant createdAt) {}
}
