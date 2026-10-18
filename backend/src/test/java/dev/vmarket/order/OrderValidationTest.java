package dev.vmarket.order;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderValidationTest {
    @Test
    void rejectsANullPaymentMethod() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var customer =
                    new CustomerRequest("Yuki", "yuki@example.jp", "100-0001", "Tokyo", "Chiyoda", "1-1", null, true);
            assertThat(factory.getValidator().validate(customer))
                    .anyMatch(
                            violation -> violation.getPropertyPath().toString().equals("paymentMethod"));
        }
    }

    @Test
    void rejectsAMissingCustomerBeforeTheServiceDereferencesIt() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new CreateOrderRequest(null, List.of(new OrderLineRequest("VM-001", 1)));
            assertThat(factory.getValidator().validate(request))
                    .anyMatch(
                            violation -> violation.getPropertyPath().toString().equals("customer"));
        }
    }
}
