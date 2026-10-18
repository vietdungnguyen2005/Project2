package dev.vmarket.order;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orders;
    private final OrderTrackingService tracking;

    public OrderController(OrderService orders, OrderTrackingService tracking) {
        this.orders = orders;
        this.tracking = tracking;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OrderResult create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Checkout-Session", required = false) String checkoutSession,
            @Valid @RequestBody CreateOrderRequest request) {
        return orders.create(checkoutSession, idempotencyKey, request);
    }

    @GetMapping("/{orderNumber}")
    TrackedOrder track(@PathVariable String orderNumber, @RequestParam String trackingToken) {
        return tracking.find(orderNumber, trackingToken);
    }

    @PostMapping("/checkout/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void closeUnused(
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Checkout-Session", required = false) String guest) {
        try {
            orders.closeUnused(guest, key);
        } catch (org.springframework.dao.CannotAcquireLockException busy) {
            throw new IdempotencyConflictException(
                    "The checkout is still active. Keep its key and check the result before trying to close it again.");
        }
    }

    @GetMapping("/checkout")
    OrderResult recover(
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Checkout-Session", required = false) String guest) {
        return orders.recover(guest, key);
    }
}
