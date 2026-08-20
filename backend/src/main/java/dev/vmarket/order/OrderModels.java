package dev.vmarket.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

record CreateOrderRequest(
        @Valid CustomerRequest customer, @NotEmpty List<@Valid OrderLineRequest> lines) {}

record CustomerRequest(
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "^[0-9]{3}-?[0-9]{4}$") String postalCode,
        @NotBlank @Size(max = 40) String prefecture,
        @NotBlank @Size(max = 80) String city,
        @NotBlank @Size(max = 160) String addressLine,
        @Pattern(regexp = "^(INVOICE|COD)$") String paymentMethod,
        @AssertTrue boolean privacyAccepted) {}

record OrderLineRequest(@NotBlank @Size(max = 64) String sku, int quantity) {}

record OrderResult(
        String orderNumber,
        String trackingToken,
        String paymentStatus,
        String fulfillmentStatus,
        int subtotalMinor,
        int shippingMinor,
        int taxMinor,
        int grandTotalMinor,
        String currency,
        Instant createdAt) {}

record TrackedOrder(
        String orderNumber,
        String paymentStatus,
        String fulfillmentStatus,
        int grandTotalMinor,
        String currency,
        Instant createdAt,
        List<TrackedItem> items) {}

record TrackedItem(String sku, String name, int unitPriceMinor, int quantity) {}
