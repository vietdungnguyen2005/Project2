package dev.vmarket.ops;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

record FulfillmentTransition(@NotBlank String status) {}

record OpsOrder(
        String orderNumber,
        String paymentStatus,
        String fulfillmentStatus,
        int grandTotalMinor,
        String currency,
        Instant createdAt,
        List<OpsOrderLine> lines) {}

record OpsOrderLine(String sku, String productName, int quantity) {}
