package dev.vmarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CheckoutTrackingTokensTest {
    @Test
    void protectedReplayIsBoundToItsOrderAndRejectsTamperingOrTheWrongKey() {
        var tokens = new CheckoutTrackingTokens(CheckoutTrackingTokens.LOCAL_KEY, "local");
        var order = java.util.UUID.randomUUID();
        String token = tokens.create();
        String encrypted = tokens.protect(order, token);
        assertThat(encrypted).doesNotContain(token);
        assertThat(tokens.reveal(order, encrypted, TokenHash.sha256(token))).isEqualTo(token);
        assertThatThrownBy(() -> tokens.reveal(java.util.UUID.randomUUID(), encrypted, TokenHash.sha256(token)))
                .isInstanceOf(IdempotencyConflictException.class);
        byte[] packet = java.util.Base64.getUrlDecoder().decode(encrypted);
        packet[packet.length - 1] ^= 1;
        String changed = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(packet);
        assertThatThrownBy(() -> tokens.reveal(order, changed, TokenHash.sha256(token)))
                .isInstanceOf(IdempotencyConflictException.class);
        byte[] otherKey = new byte[32];
        java.util.Arrays.fill(otherKey, (byte) 1);
        var other = new CheckoutTrackingTokens(java.util.Base64.getEncoder().encodeToString(otherKey), "production");
        assertThatThrownBy(() -> other.reveal(order, encrypted, TokenHash.sha256(token)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void productionRejectsTheDevelopmentKeyEvenWithAlternativeBase64Padding() {
        assertThatThrownBy(() ->
                        new CheckoutTrackingTokens(CheckoutTrackingTokens.LOCAL_KEY.replace("=", ""), "production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHECKOUT_REPLAY_KEY");
    }
}
