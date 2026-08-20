package dev.vmarket.ops;

import dev.vmarket.migration.OpsAuthorizer;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ops/orders")
public class OpsOrderController {
    private final OpsOrderService orders;
    private final OpsAuthorizer authorizer;

    public OpsOrderController(OpsOrderService orders, OpsAuthorizer authorizer) {
        this.orders = orders;
        this.authorizer = authorizer;
    }

    @GetMapping
    List<OpsOrder> recent(@RequestHeader("X-V-Market-Ops-Secret") String secret) {
        authorizer.authorize(secret);
        return orders.recent();
    }

    @PatchMapping("/{orderNumber}/fulfillment")
    OpsOrder transition(
            @RequestHeader("X-V-Market-Ops-Secret") String secret,
            @PathVariable String orderNumber,
            @Valid @RequestBody FulfillmentTransition transition) {
        authorizer.authorize(secret);
        return orders.transition(orderNumber, transition.status());
    }
}
